package com.example.read.ui.reader

/**
 * 章节内搜索（find-in-page）所需的 JavaScript / CSS 常量。
 *
 * v4 新特性入口：用户在阅读器顶栏点击搜索按钮，输入关键词后，由
 * [FindInPageController] 调用 evaluateJavascript 把以下脚本注入到当前章节的
 * WebView 上下文，从而在 DOM 层完成高亮 / 上下导航 / 计数 / 清理。
 *
 * 设计要点：
 * - 完全在 JS 端持有状态（matches 数组、currentIndex），原生只通过函数调用驱动，
 *   返回值通过 evaluateJavascript 的 ValueCallback 取得 —— 不需要 AndroidBridge
 *   nonce 校验（不存在 JS → 原生的回调路径），简化了与 P0-3 安全模型的耦合
 * - 使用 TreeWalker 仅遍历文本节点，避免修改 <script>/<style> 内联内容
 * - 用 <mark class="reader-find"> 包裹匹配项，配合阅读 CSS 渲染黄色高亮；
 *   当前选中项再加 reader-find-current class 渲染橙色 + scrollIntoView
 * - 大小写不敏感（regex flag 'i'），并对正则元字符做转义
 * - clear() 通过 parent.replaceChild(textNode, mark) 还原原始文本节点，再调用
 *   parent.normalize() 把相邻 TextNode 合并，确保清理后 DOM 与初始态等价；
 *   这一点对反复搜索 / 切章节非常重要，避免 DOM 累积膨胀
 *
 * 注入点：[ChapterWebViewFactory.loadHtml] 的 onPageFinished 中、PAGINATION_JS
 * 之后注入；CSS 由 [ReadingSettings.toReaderCss] 在样式里附带，与阅读样式同步更新。
 */
object FindInPageJs {

    /**
     * 章内搜索的 JavaScript 实现，注入到每个章节 WebView 的全局作用域。
     *
     * 暴露 API：
     * - `window.ReaderFind.find(query)` -> String（JSON `{"count":c,"index":i,"page":p}`；无匹配时 `{"count":0,"index":-1,"page":-1}`）
     * - `window.ReaderFind.next()`      -> String（JSON `{"index":i,"page":p}`；无匹配时 `{"index":-1,"page":-1}`）
     * - `window.ReaderFind.prev()`      -> String（JSON `{"index":i,"page":p}`；无匹配时 `{"index":-1,"page":-1}`）
     * - `window.ReaderFind.clear()`     -> undefined
     * - `window.ReaderFind.count()`     -> Number
     * - `window.ReaderFind.current()`   -> Number
     *
     * 使用 IIFE 把局部状态封装在闭包内，外部只能通过返回的对象操作，避免污染全局。
     * 不依赖 AndroidBridge，所有结果通过 evaluateJavascript 的 ValueCallback 回传。
     *
     * P1-v5-2 修复（next/prev 返回值升级为 JSON）：
     * - 旧实现 navigate(delta) 返回纯数字 currentIndex，但 scrollIntoView 仅在 WebView
     *   内部 scrollY 上滚动，不会触发 ViewPager2 翻页。当匹配项位于章内第 2+ 页时，
     *   高亮存在 DOM 中但用户看不到。
     * - 新实现：navigate(delta) 同时计算匹配项所在的章内页码（pageInChapter），
     *   通过返回 JSON `{"index":i,"page":p}` 回传到 Kotlin 端；Kotlin 端再驱动 ViewPager2
     *   翻到目标章内页。
     *
     * P1-v6-2 修复（find 也升级为 JSON）：
     * - 旧实现 find(query) 返回纯数字 matches.length，丢弃了内部 navigate(0) 算出的
     *   首次定位 pageInChapter。导致全书搜索点击结果跨章跳转后，用户停在新章第 1 页，
     *   即使匹配项位于第 5 页也只能看到 mark 高亮（如果第 1 页恰好有 mark）或什么都
     *   看不到（如果第 1 页无 mark）。
     * - 新实现：find(query) 改为返回 JSON `{"count":c, "index":i, "page":p}`。
     *   count 字段保留 v4 / v5 的匹配总数语义；index / page 与 navigate 同构，
     *   方便 Kotlin 端统一解析。无匹配时返回 `{"count":0, "index":-1, "page":-1}`。
     *   Controller 解析后回调 NavigateResult，让 ViewModel 在 attachFindController
     *   消费 pendingFindAfterJump 时驱动 ViewPager2 翻到匹配所在的章内页。
     */
    const val FIND_IN_PAGE_JS = """
(function() {
    var matches = [];
    var currentIndex = -1;

    // 把当前所有 <mark class="reader-find"> 还原为原始文本节点，重置状态
    function clear() {
        // toReplace 收集要还原的父节点，最后统一 normalize 合并相邻 TextNode
        var parents = [];
        matches.forEach(function(m) {
            var p = m.parentNode;
            if (!p) return;
            p.replaceChild(document.createTextNode(m.textContent), m);
            if (parents.indexOf(p) === -1) parents.push(p);
        });
        parents.forEach(function(p) {
            try { p.normalize(); } catch (e) {}
        });
        matches = [];
        currentIndex = -1;
    }

    // 在当前 document.body 中查找 query 并高亮所有匹配项，返回 JSON 字符串
    // P1-v6-2 修复：返回 JSON {"count":c, "index":i, "page":p}（替代旧的纯 matches.length）
    // - count: 匹配总数（语义同 v5 contract）
    // - index: 首个被选中匹配的 0-based 索引，无匹配时 -1
    // - page : 首个匹配所在的章内页码（0-based），无匹配 / 计算失败时 -1
    // Kotlin 端 FindInPageController.find 解析后回调 NavigateResult，
    // 让 ViewModel 在跨章跳转后 attachFindController 时驱动 ViewPager2 翻到匹配所在页。
    function find(query) {
        clear();
        if (!query) {
            return JSON.stringify({count: 0, index: -1, page: -1});
        }
        // 转义正则元字符，确保用户输入按字面量匹配；大小写不敏感
        var escaped = query.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&');
        var regex = new RegExp(escaped, 'gi');
        // TreeWalker 仅遍历文本节点，过滤 SCRIPT/STYLE 的内联内容
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
        var node;
        var toReplace = [];
        while ((node = walker.nextNode())) {
            if (!node.nodeValue) continue;
            var parent = node.parentNode;
            if (!parent) continue;
            var tag = parent.tagName;
            if (tag === 'SCRIPT' || tag === 'STYLE') continue;
            // test 会推进 lastIndex，先暂存所有命中节点再批量处理，避免遍历中修改 DOM
            regex.lastIndex = 0;
            if (regex.test(node.nodeValue)) {
                toReplace.push(node);
            }
        }
        toReplace.forEach(function(textNode) {
            var text = textNode.nodeValue;
            var frag = document.createDocumentFragment();
            var last = 0;
            var m;
            regex.lastIndex = 0;
            while ((m = regex.exec(text)) !== null) {
                if (m.index > last) {
                    frag.appendChild(document.createTextNode(text.substring(last, m.index)));
                }
                var mark = document.createElement('mark');
                mark.className = 'reader-find';
                mark.textContent = m[0];
                frag.appendChild(mark);
                matches.push(mark);
                last = m.index + m[0].length;
                // 防止零长度匹配死循环（理论上 query 不为空时不会发生，但保留兜底）
                if (regex.lastIndex === m.index) regex.lastIndex++;
            }
            if (last < text.length) {
                frag.appendChild(document.createTextNode(text.substring(last)));
            }
            if (textNode.parentNode) {
                textNode.parentNode.replaceChild(frag, textNode);
            }
        });
        // 命中后默认选中第一个匹配项并滚动可见
        // P1-v6-2：捕获 navigate(0) 的 JSON 结果（含 pageInChapter），合并到 find 的返回值
        if (matches.length > 0) {
            var navJson = navigate(0);
            var nav;
            try { nav = JSON.parse(navJson); } catch (e) { nav = {index: 0, page: -1}; }
            return JSON.stringify({
                count: matches.length,
                index: (typeof nav.index === 'number') ? nav.index : 0,
                page : (typeof nav.page  === 'number') ? nav.page  : -1
            });
        }
        return JSON.stringify({count: 0, index: -1, page: -1});
    }

    // 计算给定 mark 元素相对于文档左侧的偏移量，得到其所在的章内页码（0-based）。
    // 章节分页原理：WebView 内容横向铺开（虽然 body 内是纵向流式，但分页 JS 通过
    // 滚动方式呈现 —— 我们的 PAGINATION_JS 使用 window.scrollTo 纵向滚动）。
    // 因此实际章内页码由 mark.offsetTop / window.innerHeight 决定（纵向分页）。
    // 注意：本工程的 WebViewPaginator 使用纵向 scrollTo(0, pageIndex * innerHeight)
    // 进行翻页，所以这里以 offsetTop 计算 pageInChapter 与原生分页保持一致。
    function pageOfMatch(target) {
        if (!target) return -1;
        try {
            // 使用 getBoundingClientRect().top + window.scrollY 得到文档绝对偏移，
            // 兼容嵌套定位容器（offsetTop 在嵌套定位下不可靠）
            var rect = target.getBoundingClientRect();
            var absoluteTop = rect.top + (window.scrollY || window.pageYOffset || 0);
            var viewportH = window.innerHeight || document.documentElement.clientHeight || 0;
            if (viewportH <= 0) return 0;
            return Math.max(0, Math.floor(absoluteTop / viewportH));
        } catch (e) {
            return -1;
        }
    }

    // 在匹配项之间导航：delta=0 选第一个，delta=1 下一个，delta=-1 上一个
    // 返回 JSON 字符串 `{"index":i,"page":p}`（旧版返回纯数字 currentIndex）。
    // p 是匹配项所在的章内页码（0-based），由 Kotlin 端用于驱动 ViewPager2 翻页。
    function navigate(delta) {
        if (matches.length === 0) {
            return JSON.stringify({index: -1, page: -1});
        }
        // 先取消旧选中项的橙色高亮
        if (currentIndex >= 0 && currentIndex < matches.length) {
            matches[currentIndex].classList.remove('reader-find-current');
        }
        if (delta === 0) {
            currentIndex = 0;
        } else {
            currentIndex = (currentIndex + delta + matches.length) % matches.length;
        }
        var target = matches[currentIndex];
        target.classList.add('reader-find-current');
        try {
            target.scrollIntoView({block: 'center'});
        } catch (e) {
            // 老版 WebView 不支持 options 参数，回退到无参数 scrollIntoView
            try { target.scrollIntoView(); } catch (e2) {}
        }
        // 回传匹配项的章内页码，让原生端联动 ViewPager2 翻页（P1-v5-2）
        var pageInChapter = pageOfMatch(target);
        return JSON.stringify({index: currentIndex, page: pageInChapter});
    }

    window.ReaderFind = {
        find: find,
        next: function() { return navigate(1); },
        prev: function() { return navigate(-1); },
        clear: clear,
        count: function() { return matches.length; },
        current: function() { return currentIndex; }
    };
})();
"""

    /**
     * 搜索高亮配套的 CSS 片段。
     *
     * 用法：由 [ReadingSettings.toReaderCss] 在拼装阅读样式时附加到末尾，
     * 这样每次 updateCSS 都会同步重置 mark 样式，避免主题切换后高亮失效。
     *
     * 颜色选择：
     * - 普通匹配：#FFE066（柔和黄色），与白/护眼/暗黑三种背景均有足够对比度
     * - 当前选中：#FF9933（橙色），比普通匹配更醒目
     * - color: inherit 让 mark 内文字继承正文颜色，暗黑主题下不会因为浏览器默认
     *   mark 字色（黑色）造成低对比
     */
    const val FIND_IN_PAGE_CSS = """
mark.reader-find { background-color: #FFE066; color: inherit; padding: 0 1px; border-radius: 2px; }
mark.reader-find-current { background-color: #FF9933; color: inherit; }
"""
}
