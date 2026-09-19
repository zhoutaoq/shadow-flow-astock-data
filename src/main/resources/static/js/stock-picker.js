/* =========================================================================
 * 股票选择器
 *
 * 页面上所有"股票"参数都用它：输入代码或名称 → 模糊匹配前 N 条 → 从结果里点选，
 * 不允许随手敲一个不存在的编号（那只会换来一次按次计费的无效调用）。
 *
 * 单选：createStockPicker(el, {})
 * 多选：createStockPicker(el, {multiple: true})
 * 候选只来自某个固定集合（如账户页「卖出」只能选持仓里的标的）：
 *     createStockPicker(el, {source: () => [{code, name}, ...], allowManual: false})
 *
 * 返回对象：
 *   getCodes()            已选代码数组
 *   getItems()            已选 [{code, name}]
 *   getFirst()            单选的那一支（多选返回第一支）
 *   hasUnresolvedInput()  输入框有字但没点选 → 提交前拦截用
 *   getInputText()        输入框当前文本（用于提示"你填的 XXX 没有对应标的"）
 *   setItems(list)        外部同步已选（不触发 onChange，避免循环）
 *   setPlaceholder(text)  换掉输入框提示（方向切换时文案跟着变）
 *   refresh(kw)           重新取一次候选（不自动展开下拉）
 *   clear()               清空
 *   pickByCode(code)      按代码反查并选中（从 URL 带参数进来时用）
 *   focus()
 * ========================================================================= */

/** 下拉里最多展示的候选条数 */
const STOCK_PICKER_LIMIT = 10;

/** 输入停顿多久才真的去查一次 */
const STOCK_PICKER_DEBOUNCE_MS = 200;

/** 下拉的最大高度（与 common.css 的 .sp-drop max-height 一致） */
const STOCK_PICKER_DROP_MAX_H = 288;
/** 下拉与输入框的间距 */
const STOCK_PICKER_DROP_GAP = 4;
/** 下拉离视口边缘至少留多少 */
const STOCK_PICKER_EDGE = 8;
/** 下拉宽度区间：跟输入框同宽，但不窄于 260 / 不宽于 420 */
const STOCK_PICKER_DROP_MIN_W = 260;
const STOCK_PICKER_DROP_MAX_W = 420;

    function createStockPicker(host, opts) {
    opts = opts || {};
    const multiple = !!opts.multiple;
    const limit = opts.limit || STOCK_PICKER_LIMIT;
    const onChange = opts.onChange || function () {};
    const onEnter = opts.onEnter || null;
    // 无数据库模式下名录可能不全，允许直接手输一个纯数字代码（本地 JSON 没覆盖到的新标的）
    const allowManual = typeof opts.allowManual === 'function'
        ? opts.allowManual
        : () => !!(opts.allowManual || (typeof MODE !== 'undefined' && MODE.noDb));
    /**
     * 候选来源（可选）：给了就只从它返回的清单里选，不再去问 /api/dir/stocks。
     *
     * <p>用在「候选天然就是一个小集合」的场景 —— 账户页「卖出」只能卖持仓里的标的，
     * 让用户从名录里随便挑一个再被后端拒绝，体验与实现都不对。函数可以是异步的，
     * 返回 {@code null} 表示这次走回默认的名录查询（同一选择器可随页面状态来回切）。
     */
    const source = typeof opts.source === 'function' ? opts.source : null;
    /** 候选为空时的提示（字符串或 {@code (kw) => string}）；返回空则用通用文案 */
    const emptyTip = opts.emptyTip || null;
    /** chips 用 grid 布局时的列数（0 或 undefined = 行内默认样式） */
    const gridColumns = opts.gridColumns || 0;

    host.classList.add('sp');
    host.innerHTML =
        '<div class="sp-box">'
        + '<span class="sp-chips"></span>'
        + '<input class="sp-input" type="text" autocomplete="off">'
        + '<span class="sp-clear" title="清除">×</span>'
        + '</div>'
        + '<div class="sp-drop"></div>';

    const boxEl = host.querySelector('.sp-box');
    const chipsEl = host.querySelector('.sp-chips');
    if (gridColumns > 0) {
        // grid 模式：sp-box 整体变 N 列网格，已选 chip 和输入框都进同一网格；
        // sp-chips 用 display:contents 把内部 chip 平铺到父级网格的 item 中。
        boxEl.classList.add('sp-box-grid');
        boxEl.style.gridTemplateColumns = 'repeat(' + gridColumns + ', minmax(0, 1fr))';
    }
    const inputEl = host.querySelector('.sp-input');
    const clearEl = host.querySelector('.sp-clear');
    const dropEl = host.querySelector('.sp-drop');

    inputEl.placeholder = opts.placeholder || '代码或名称';

    let items = [];      // 多选已选
    let single = null;   // 单选已选
    let options = [];    // 当前候选
    let activeIdx = -1;
    let seq = 0;         // 请求序号，丢弃过期响应
    let timer = null;
    let lastKw = '';

    function isPicked(code) {
        return multiple ? items.some(x => x.code === code) : (!!single && single.code === code);
    }

    function label(it) {
        return it.code + (it.name ? ' ' + it.name : '');
    }

    function renderChips() {
        if (!multiple) {
            chipsEl.innerHTML = '';
            return;
        }
        // grid 模式：每个 chip 都带省略号的紧凑样式（grid item 可能被压缩）
        if (boxEl.classList.contains('sp-box-grid')) {
            chipsEl.innerHTML = items.map((it, i) =>
                '<span class="sp-chip"><b>' + esc(it.code) + '</b>'
                + '<span>' + esc(it.name || '') + '</span>'
                + '<i data-i="' + i + '" title="移除">×</i></span>'
            ).join('');
        } else {
            chipsEl.innerHTML = items.map((it, i) =>
                '<span class="sp-chip"><b>' + esc(it.code) + '</b> ' + esc(it.name || '')
                + '<i data-i="' + i + '" title="移除">×</i></span>'
            ).join('');
        }
        Array.prototype.forEach.call(chipsEl.querySelectorAll('.sp-chip i'), el => {
            el.addEventListener('mousedown', ev => {
                ev.preventDefault();
                ev.stopPropagation();
                items.splice(parseInt(el.getAttribute('data-i'), 10), 1);
                renderChips();
                onChange();
            });
        });
    }

    function renderDrop(kw) {
        renderDropBody(kw);
        // 候选条数变了高度就变了，重新摆一次（比如从"搜索中"变成 10 条）
        if (isShown()) {
            positionDrop();
        }
    }

    /** 没有候选时的提示文案：页面可用 opts.emptyTip 覆盖 */
    function emptyText(kw) {
        const custom = typeof emptyTip === 'function' ? emptyTip(kw) : emptyTip;
        if (custom) {
            return custom;
        }
        return '没有匹配的股票' + (kw ? '：' + kw : '')
            + (allowManual() ? '，可直接输入完整代码后回车' : '');
    }

    function renderDropBody(kw) {
        if (!options.length) {
            dropEl.innerHTML = '<div class="sp-tip">' + esc(emptyText(kw)) + '</div>';
            return;
        }
        dropEl.innerHTML = options.map((o, i) => {
            const picked = isPicked(o.code);
            return '<div class="sp-opt' + (i === activeIdx ? ' active' : '')
                + (picked ? ' picked' : '') + '" data-i="' + i + '">'
                + '<span class="code">' + esc(o.code) + '</span>'
                + '<span class="name">' + esc(o.name || '') + '</span>'
                + (picked ? '<span class="tick">✓ 已选</span>' : '')
                + '</div>';
        }).join('')
            + '<div class="sp-tip">共 ' + options.length + ' 条'
            + (options.length >= limit ? '，仅显示前 ' + limit + ' 条' : '')
            + '</div>';
        Array.prototype.forEach.call(dropEl.querySelectorAll('.sp-opt'), el => {
            el.addEventListener('mousedown', ev => ev.preventDefault());
            el.addEventListener('click', () => pick(options[parseInt(el.getAttribute('data-i'), 10)]));
        });
    }

    function isShown() {
        return dropEl.classList.contains('show');
    }

    /**
     * 展开下拉时把它挂到 <code>body</code> 上，改为 <code>position: fixed</code> 定位。
     *
     * <p>为什么必须这么干：卡片、面板这类容器为了把装饰元素裁进圆角，普遍带
     * <code>overflow: hidden</code>，而选择器的下拉是绝对定位的，会被父级裁成一条缝
     * （盯盘页实测 288px 的下拉只剩 13px 可见）。挨个去改容器的 overflow 治不了根 ——
     * 那些 overflow 是圆角装饰需要的，删了会露出溢出的高亮条（比如回测页 .cfg-card）。
     * 挂到 body 上，任何祖先都裁不到它。
     */
    function mountDrop() {
        if (dropEl.parentNode !== document.body) {
            document.body.appendChild(dropEl);
        }
        dropEl.classList.add('sp-drop-fixed');
    }

    /** 收起时放回宿主里，保持 DOM 就近（宿主被整块替换时也不会留孤儿节点在 body 上） */
    function unmountDrop() {
        dropEl.classList.remove('sp-drop-fixed');
        if (dropEl.parentNode !== host) {
            host.appendChild(dropEl);
        }
    }

    /**
     * 把下拉摆到「输入框正下方」，下方不够就翻到上方。
     *
     * <p>除了躲开祖先裁剪，还解决两件事：
     * <ol>
     *   <li>选择器在页面下半部分时，往下展开会跑到视口外，得翻上去；</li>
     *   <li>多选 + 网格模式下 <code>.sp</code> 比输入框高得多，贴着 <code>.sp</code> 的底边
     *       会让下拉离输入框几百像素，所以一律以输入框为锚点。</li>
     * </ol>
     * 因为是 fixed 定位，这里全部用视口坐标（getBoundingClientRect 本来就是视口坐标）。
     */
    function positionDrop() {
        if (!isShown()) {
            return;
        }
        mountDrop();
        const anchor = inputEl.getBoundingClientRect();

        // 先定水平位置与宽度，再量内容高度（宽度影响换行，量早了不准）
        const width = Math.min(Math.max(anchor.width, STOCK_PICKER_DROP_MIN_W), STOCK_PICKER_DROP_MAX_W);
        let left = anchor.left;
        const maxLeft = window.innerWidth - STOCK_PICKER_EDGE - width;
        if (left > maxLeft) {
            left = Math.max(STOCK_PICKER_EDGE, maxLeft);
        }
        if (left < STOCK_PICKER_EDGE) {
            left = STOCK_PICKER_EDGE;
        }
        dropEl.style.left = left + 'px';
        dropEl.style.width = width + 'px';

        // scrollHeight 是内容真实高度（不受 max-height 影响）
        const want = Math.min(STOCK_PICKER_DROP_MAX_H, dropEl.scrollHeight);
        const spaceBelow = window.innerHeight - anchor.bottom - STOCK_PICKER_DROP_GAP - STOCK_PICKER_EDGE;
        const spaceAbove = anchor.top - STOCK_PICKER_DROP_GAP - STOCK_PICKER_EDGE;

        let up;
        let h;
        if (spaceBelow >= want) {
            up = false;
            h = want;
        } else if (spaceAbove >= want) {
            up = true;
            h = want;
        } else {
            // 上下都塞不下：挑空间大的一侧，能露多少露多少（下拉自身可滚动）
            up = spaceAbove > spaceBelow;
            h = Math.max(0, up ? spaceAbove : spaceBelow);
        }

        const height = h > 0 ? h : want;
        dropEl.style.maxHeight = height + 'px';
        dropEl.style.right = 'auto';
        dropEl.style.bottom = 'auto';
        if (up) {
            dropEl.style.top = Math.max(STOCK_PICKER_EDGE, anchor.top - STOCK_PICKER_DROP_GAP - height) + 'px';
            dropEl.classList.add('up');
        } else {
            dropEl.style.top = (anchor.bottom + STOCK_PICKER_DROP_GAP) + 'px';
            dropEl.classList.remove('up');
        }
    }

    function open() {
        dropEl.classList.add('show');
        mountDrop();
        positionDrop();
    }

    function close() {
        dropEl.classList.remove('show');
        activeIdx = -1;
        unmountDrop();
    }

    function pick(it) {
        if (!it) return;
        const one = {code: it.code, name: it.name || ''};
        if (multiple) {
            const idx = items.findIndex(x => x.code === it.code);
            if (idx >= 0) items.splice(idx, 1);
            else items.push(one);
            renderChips();
            inputEl.value = '';
            onChange();
            search(lastKw);
            inputEl.focus();
        } else {
            single = one;
            inputEl.value = label(one);
            close();
            onChange();
        }
    }

    /**
     * 取候选。
     *
     * <p>默认走服务端匹配：每次输入停顿后带着关键字去问一次，server 返回排好序的 topN。
     * 名录不再下发到客户端（v1.2：股票集合常驻服务端内存，前端不缓存）；
     * 服务端用并行 String[] 紧凑存储，5436 条只占约 350KB，匹配是单次 O(n) 扫描+少量排序，
     * 8 核 1.7G 内存机器上 p95 < 5ms，前端 200ms 防抖 + 串行请求足够。
     *
     * <p>给了 {@code opts.source}（且它返回了清单）时改成在本地过滤 —— 候选本来就是一小撮
     * （例如「持仓里的标的」），不值得也不该去问名录。
     *
     * @param {boolean} silent true = 只把候选准备好，不主动展开下拉（方向切换后刷新用）
     */
    async function search(kw, silent) {
        lastKw = kw || '';
        const my = ++seq;
        if (source) {
            const all = await source();
            if (my !== seq) return;
            // 返回清单（哪怕是空数组）= 只在本地过滤；返回 null = 这次不走固定集合，
            // 回落到默认名录查询 —— 账户页「卖出」给持仓清单、「买入」给 null，共用一个选择器
            if (all) {
                const k = lastKw.trim().toLowerCase();
                options = (k
                    ? all.filter(x => String(x.code || '').toLowerCase().indexOf(k) >= 0
                        || String(x.name || '').toLowerCase().indexOf(k) >= 0)
                    : all).slice(0, limit);
                activeIdx = options.length ? 0 : -1;
                renderDropBody(lastKw);
                if (isShown()) {
                    positionDrop();
                } else if (!silent) {
                    open();
                }
                return;
            }
            // 回落前先丢掉上一轮的候选，免得把持仓清单带进「买入」方向
            options = [];
        }
        const q = ['limit=' + limit];
        if (lastKw) q.push('keyword=' + encodeURIComponent(lastKw));
        // 空查询不打招呼，节省请求；按规范输入第一个字再走接口
        if (lastKw === '' && !options.length) {
            dropEl.innerHTML = '<div class="sp-tip">代码或名称</div>';
            if (!silent) open();
            return;
        }
        dropEl.innerHTML = '<div class="sp-tip"><span class="spinner"></span> 搜索中...</div>';
        if (!silent) open();
        const r = await get('/api/dir/stocks?' + q.join('&'));
        if (my !== seq) return;
        if (!r.success) {
            dropEl.innerHTML = '<div class="sp-tip">查询失败：' + esc(r.message) + '</div>';
            return;
        }
        // 只认前 limit 条：服务端一般已经按 limit 截断，这里再兜一次，
        // 免得哪天接口返回多了一眼看到一屏候选、还得往下滚才找得到
        options = (r.data || []).slice(0, limit).map(x => ({code: x.code, name: x.name || ''}));
        activeIdx = options.length ? 0 : -1;
        renderDrop(lastKw);
    }

    function move(d) {
        if (!dropEl.classList.contains('show')) {
            search(inputEl.value.trim());
            return;
        }
        if (!options.length) return;
        activeIdx = (activeIdx + d + options.length) % options.length;
        renderDrop(lastKw);
        const el = dropEl.querySelector('.sp-opt.active');
        if (el && el.scrollIntoView) el.scrollIntoView({block: 'nearest'});
    }

    function clearAll() {
        items = [];
        single = null;
        inputEl.value = '';
        lastKw = '';
        renderChips();
        onChange();
    }

    boxEl.addEventListener('mousedown', ev => {
        // 点输入框自己不要拦，否则拖选文本会被禁掉
        if (ev.target === clearEl || ev.target === inputEl) return;
        ev.preventDefault();
        inputEl.focus();
    });

    inputEl.addEventListener('focus', () => {
        // 单选已选中时，输入框里是「代码 名称」而不是关键字，不能直接拿去搜
        const showingLabel = !multiple && single;
        if (showingLabel) inputEl.select();
        const kw = inputEl.value.trim();
        if (kw && !showingLabel) {
            // 还有没点选的关键字：保持它的结果（含"没有匹配"的提示），不要冲成默认列表
            search(kw);
        } else if (!options.length) {
            search('');
        } else {
            open();
        }
    });
    inputEl.addEventListener('blur', () => setTimeout(close, 120));
    inputEl.addEventListener('input', () => {
        if (!multiple) {
            single = null;
            onChange();
        }
        const kw = inputEl.value.trim();
        clearTimeout(timer);
        timer = setTimeout(() => search(kw), STOCK_PICKER_DEBOUNCE_MS);
    });
    inputEl.addEventListener('keydown', e => {
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            move(1);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            move(-1);
        } else if (e.key === 'Enter') {
            e.preventDefault();
            if (activeIdx >= 0 && options[activeIdx]) pick(options[activeIdx]);
            else if (multiple) { /* 多选靠点选，回车不追加 */ }
            else if (tryManual()) close();
            else if (onEnter) onEnter();
        } else if (e.key === 'Escape') {
            close();
            inputEl.blur();
        } else if (e.key === 'Backspace' && multiple && !inputEl.value && items.length) {
            items.pop();
            renderChips();
            onChange();
        }
    });

    clearEl.addEventListener('mousedown', ev => ev.preventDefault());
    clearEl.addEventListener('click', () => {
        clearAll();
        inputEl.focus();
    });

    // 视口变化 / 页面滚动时下拉要跟着输入框走（下拉是绝对定位，不跟会飘）
    // 捕获阶段监听是为了顺手拿到内部滚动容器（.table-wrap 之类）的滚动事件
    window.addEventListener('resize', positionDrop);
    window.addEventListener('scroll', positionDrop, true);

    /**
     * 手输兜底：名录里没有（或还没加载）时，把输入框里的纯数字代码直接当选中项。
     *
     * <p>只有单选且开启 allowManual 才生效 —— 多选场景必须逐一点选，不能靠猜。
     */
    function tryManual() {
        if (multiple || !allowManual()) {
            return false;
        }
        const v = inputEl.value.trim();
        if (!/^\d{5,6}$/.test(v)) {
            return false;
        }
        single = {code: v, name: ''};
        inputEl.value = label(single);
        onChange();
        return true;
    }

    return {
        getCodes() {
            return multiple ? items.map(x => x.code) : (single ? [single.code] : []);
        },
        getItems() {
            return multiple ? items.slice() : (single ? [single] : []);
        },
        getFirst() {
            return multiple ? (items[0] || null) : single;
        },
        /** 提交前把"手输但还没点选"的代码补成选中项；不是合法代码返回 false */
        tryManual,
        hasUnresolvedInput() {
            return !!inputEl.value.trim() && this.getCodes().length === 0;
        },
        getInputText() {
            return inputEl.value.trim();
        },
        /** 外部同步已选（表格勾选 ↔ 已选标签双向同步），不触发 onChange */
        setItems(list) {
            const arr = (list || []).map(x => ({code: x.code, name: x.name || ''}));
            if (multiple) {
                items = arr;
                renderChips();
            } else {
                single = arr.length ? arr[0] : null;
                inputEl.value = single ? label(single) : '';
            }
        },
        clear: clearAll,
        /** 换掉输入框提示文案（方向切换时「从持仓里选」这类提示要跟着变） */
        setPlaceholder(text) {
            inputEl.placeholder = text || '';
        },
        /**
         * 重新取一次候选，<b>不自动展开下拉</b>（用户点输入框时才展开）。
         * 用在「同一选择器的候选会变」的场景：账户页切到「卖出」后候选换成持仓里的标的。
         */
        refresh(kw) {
            return search(kw || '', true);
        },
        /** 按代码反查并选中；列表里没有时单选只填文本不算选中（提交会被拦截） */
        async pickByCode(code) {
            if (!code) return false;
            const q = ['limit=' + limit, 'keyword=' + encodeURIComponent(code)];
            const r = await get('/api/dir/stocks?' + q.join('&'));
            const hit = (r && r.success ? (r.data || []) : []).find(x => x.code === code);
            if (!hit) {
                if (!multiple) {
                    single = null;
                    inputEl.value = code;
                    onChange();
                }
                return false;
            }
            pick({code: hit.code, name: hit.name || ''});
            return true;
        },
        focus() {
            inputEl.focus();
        }
    };
}

/**
 * 提交前校验：必须已从候选里点选。
 * 有输入但没点选时给出明确提示，而不是拿用户敲的编号直接去请求。
 */
function requireStock(picker, what) {
    if (picker.getCodes().length) return true;
    // 手输兜底：名录里没有的标的代码，允许直接敲完整代码提交
    if (typeof picker.tryManual === 'function' && picker.tryManual()) return true;
    const typed = picker.getInputText();
    toast(typed
        ? '「' + typed + '」还没有选定，请从下拉列表里点选' + (what || '一只股票')
        : '请先选择' + (what || '一只股票'),
        'err');
    picker.focus();
    return false;
}
