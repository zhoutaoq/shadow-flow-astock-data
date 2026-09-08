/* =========================================================================
 * 股票选择器
 *
 * 页面上所有"股票"参数都用它：输入代码或名称 → 模糊匹配前 N 条 → 从结果里点选，
 * 不允许随手敲一个不存在的编号（那只会换来一次按次计费的无效调用）。
 *
 * 单选：createStockPicker(el, {})
 * 多选：createStockPicker(el, {multiple: true})
 *
 * 返回对象：
 *   getCodes()            已选代码数组
 *   getItems()            已选 [{code, name}]
 *   getFirst()            单选的那一支（多选返回第一支）
 *   hasUnresolvedInput()  输入框有字但没点选 → 提交前拦截用
 *   getInputText()        输入框当前文本（用于提示"你填的 XXX 没有对应标的"）
 *   setItems(list)        外部同步已选（不触发 onChange，避免循环）
 *   clear()               清空
 *   pickByCode(code)      按代码反查并选中（从 URL 带参数进来时用）
 *   focus()
 * ========================================================================= */

/** 下拉里最多展示的候选条数 */
const STOCK_PICKER_LIMIT = 10;

/** 输入停顿多久才真的去查一次 */
const STOCK_PICKER_DEBOUNCE_MS = 200;

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

    inputEl.placeholder = opts.placeholder
        || (multiple ? '输入代码或名称，点选加入' : '输入代码或名称，从结果中点选');

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
        if (!options.length) {
            dropEl.innerHTML = '<div class="sp-tip">没有匹配的股票'
                + (kw ? '：' + esc(kw) : '')
                + (allowManual()
                    ? '。可直接输入完整代码后回车（或到「系统配置」检查令牌）'
                    : '。可换个关键字，或先到「股票列表管理」导入股票列表')
                + '</div>';
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
            + (options.length >= limit ? '，仅显示前 ' + limit + ' 条，请输入更精确的关键字' : '')
            + '</div>';
        Array.prototype.forEach.call(dropEl.querySelectorAll('.sp-opt'), el => {
            el.addEventListener('mousedown', ev => ev.preventDefault());
            el.addEventListener('click', () => pick(options[parseInt(el.getAttribute('data-i'), 10)]));
        });
    }

    function open() {
        dropEl.classList.add('show');
    }

    function close() {
        dropEl.classList.remove('show');
        activeIdx = -1;
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
     * 服务端匹配：每次输入停顿后带着关键字去问一次，server 返回排好序的 topN。
     *
     * <p>名录不再下发到客户端（v1.2：股票集合常驻服务端内存，前端不缓存）；
     * 服务端用并行 String[] 紧凑存储，5436 条只占约 350KB，匹配是单次 O(n) 扫描+少量排序，
     * 8 核 1.7G 内存机器上 p95 < 5ms，前端 200ms 防抖 + 串行请求足够。
     */
    async function search(kw) {
        lastKw = kw || '';
        const my = ++seq;
        const q = ['limit=' + limit];
        if (lastKw) q.push('keyword=' + encodeURIComponent(lastKw));
        // 空查询不打招呼，节省请求；按规范输入第一个字再走接口
        if (lastKw === '' && !options.length) {
            dropEl.innerHTML = '<div class="sp-tip">输入代码或名称开始匹配</div>';
            open();
            return;
        }
        dropEl.innerHTML = '<div class="sp-tip"><span class="spinner"></span> 搜索中...</div>';
        open();
        const r = await get('/api/dir/stocks?' + q.join('&'));
        if (my !== seq) return;
        if (!r.success) {
            dropEl.innerHTML = '<div class="sp-tip">查询失败：' + esc(r.message) + '</div>';
            return;
        }
        options = (r.data || []).map(x => ({code: x.code, name: x.name || ''}));
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
        ? '「' + typed + '」还没有选定：请输入代码或名称后，从下拉列表里点选'
        + (what || '一只股票')
        : '请先选择' + (what || '一只股票') + '：输入代码或名称，从下拉列表里点选',
        'err');
    picker.focus();
    return false;
}
