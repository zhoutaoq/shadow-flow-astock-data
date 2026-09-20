/* ============ 日期选择：可手输宽松格式，匹配后自动规范化 ============ */

const DP_WEEK = ['日', '一', '二', '三', '四', '五', '六'];

function initDatePickers(root) {
    const scope = root || document;
    scope.querySelectorAll('input[type="date"]').forEach(enhanceDateInput);
}

function enhanceDateInput(input) {
    if (!input || input.dataset.dp === '1') {
        return;
    }
    input.dataset.dp = '1';

    const wrap = document.createElement('div');
    wrap.className = 'dp';
    input.parentNode.insertBefore(wrap, input);
    wrap.appendChild(input);

    const vis = document.createElement('input');
    vis.type = 'text';
    vis.className = 'dp-input';
    vis.placeholder = input.placeholder || 'YYYY-MM-DD';
    vis.setAttribute('autocomplete', 'off');
    vis.setAttribute('spellcheck', 'false');
    vis.value = input.value || '';
    wrap.appendChild(vis);

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'dp-btn';
    btn.title = '打开日历';
    btn.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>';
    wrap.appendChild(btn);

    const pop = document.createElement('div');
    pop.className = 'dp-pop';
    wrap.appendChild(pop);

    let viewYear;
    let viewMonth;
    syncViewFromValue();

    const desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    Object.defineProperty(input, 'value', {
        configurable: true,
        get() {
            return desc.get.call(this);
        },
        set(v) {
            desc.set.call(this, v || '');
            vis.value = this.value;
            syncViewFromValue();
        }
    });

    vis.addEventListener('input', function () {
        const parsed = parseDateLoose(vis.value);
        if (parsed) {
            desc.set.call(input, parsed);
            vis.classList.remove('dp-bad');
            vis.classList.add('dp-ok');
            input.dispatchEvent(new Event('change', {bubbles: true}));
        } else {
            vis.classList.remove('dp-ok');
            if (vis.value.trim()) {
                vis.classList.add('dp-bad');
            } else {
                vis.classList.remove('dp-bad');
                desc.set.call(input, '');
                input.dispatchEvent(new Event('change', {bubbles: true}));
            }
        }
    });

    vis.addEventListener('blur', function () {
        commitVisible();
    });

    vis.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            commitVisible();
            closePop();
        } else if (e.key === 'Escape') {
            vis.value = input.value || '';
            vis.classList.remove('dp-bad', 'dp-ok');
            closePop();
        }
    });

    btn.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        if (pop.classList.contains('show')) {
            closePop();
        } else {
            openPop();
        }
    });

    vis.addEventListener('focus', function () {
        // 手输时不必强制弹出，点日历按钮再开
    });

    document.addEventListener('mousedown', function (e) {
        if (!wrap.contains(e.target)) {
            closePop();
        }
    });

    function commitVisible() {
        const raw = vis.value.trim();
        if (!raw) {
            desc.set.call(input, '');
            vis.classList.remove('dp-bad', 'dp-ok');
            input.dispatchEvent(new Event('change', {bubbles: true}));
            return;
        }
        const parsed = parseDateLoose(raw);
        if (parsed) {
            desc.set.call(input, parsed);
            vis.value = parsed;
            vis.classList.remove('dp-bad');
            vis.classList.add('dp-ok');
            setTimeout(() => vis.classList.remove('dp-ok'), 600);
            input.dispatchEvent(new Event('change', {bubbles: true}));
        } else {
            vis.classList.add('dp-bad');
            toast('日期格式无法识别，请输入如 2024-01-05 或 20240105', 'err');
        }
    }

    function syncViewFromValue() {
        const d = parseYmd(input.value) || new Date();
        viewYear = d.getFullYear();
        viewMonth = d.getMonth();
    }

    function openPop() {
        syncViewFromValue();
        renderCal();
        pop.classList.add('show');
    }

    function closePop() {
        pop.classList.remove('show');
    }

    function setValue(ymd) {
        desc.set.call(input, ymd);
        vis.value = ymd;
        vis.classList.remove('dp-bad');
        vis.classList.add('dp-ok');
        setTimeout(() => vis.classList.remove('dp-ok'), 600);
        input.dispatchEvent(new Event('change', {bubbles: true}));
        closePop();
    }

    function renderCal() {
        const selected = input.value;
        const first = new Date(viewYear, viewMonth, 1);
        const startWeek = first.getDay();
        const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
        const todayStr = today();
        const title = viewYear + '年' + String(viewMonth + 1).padStart(2, '0') + '月';

        let cells = '';
        for (let i = 0; i < startWeek; i++) {
            cells += '<span class="dp-cell dp-empty"></span>';
        }
        for (let day = 1; day <= daysInMonth; day++) {
            const ymd = padYmd(viewYear, viewMonth + 1, day);
            const cls = ['dp-cell'];
            if (ymd === selected) {
                cls.push('dp-sel');
            }
            if (ymd === todayStr) {
                cls.push('dp-today');
            }
            cells += `<button type="button" class="${cls.join(' ')}" data-d="${ymd}">${day}</button>`;
        }

        pop.innerHTML = `
            <div class="dp-head">
                <button type="button" class="dp-nav" data-act="py" title="上一年">«</button>
                <button type="button" class="dp-nav" data-act="pm" title="上一月">‹</button>
                <span class="dp-title">${title}</span>
                <button type="button" class="dp-nav" data-act="nm" title="下一月">›</button>
                <button type="button" class="dp-nav" data-act="ny" title="下一年">»</button>
            </div>
            <div class="dp-week">${DP_WEEK.map(w => '<span>' + w + '</span>').join('')}</div>
            <div class="dp-grid">${cells}</div>
            <div class="dp-foot">
                <button type="button" class="dp-link" data-act="clear">清空</button>
                <button type="button" class="dp-link" data-act="today">今天</button>
            </div>`;

        pop.querySelectorAll('[data-act]').forEach(el => {
            el.onclick = function () {
                const a = el.getAttribute('data-act');
                if (a === 'py') {
                    viewYear--;
                    renderCal();
                } else if (a === 'ny') {
                    viewYear++;
                    renderCal();
                } else if (a === 'pm') {
                    viewMonth--;
                    if (viewMonth < 0) {
                        viewMonth = 11;
                        viewYear--;
                    }
                    renderCal();
                } else if (a === 'nm') {
                    viewMonth++;
                    if (viewMonth > 11) {
                        viewMonth = 0;
                        viewYear++;
                    }
                    renderCal();
                } else if (a === 'today') {
                    setValue(today());
                } else if (a === 'clear') {
                    setValue('');
                    vis.value = '';
                    vis.classList.remove('dp-ok', 'dp-bad');
                }
            };
        });
        pop.querySelectorAll('.dp-cell[data-d]').forEach(el => {
            el.onclick = function () {
                setValue(el.getAttribute('data-d'));
            };
        });
    }
}
