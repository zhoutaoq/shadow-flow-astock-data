/* =========================================================================
 * 金牛数据 · 首页视觉
 *
 * 两块东西：
 *   1. 品牌带背景：canvas 里画真实行情（长电科技 600584 的一段日K），横向循环滚动
 *   2. 核心能力轮播：自动播放 + 左右箭头 + 圆点，示意图是内联 SVG
 *
 * 约束：
 *   - 颜色一律从 common.css 的语义令牌读，切主题自动跟着变
 *   - prefers-reduced-motion 下只画静态帧、不自动播放
 *   - 页面不可见 / 区块滚出视口时停掉 rAF，不空转
 * ========================================================================= */

/* =========================================================================
 * 背景素材：长电科技 600584 · 2026-03-02 ~ 2026-06-30（82 个交易日）
 *
 * 每行 [开, 高, 低, 收, 成交量(手)]，顺序按交易日升序。
 * 取自 /api/kline/chart?code=600584&startDate=2026-03-01&endDate=2026-06-30
 * 区间：46.77 → 103.55，区间涨幅 +121.4%；最低 38.02 / 最高 109.33。
 *
 * 换素材：整段替换即可，下面代码只依赖"数组长度 + 5 列格式"，不用改。
 * ========================================================================= */
var KC = [
    [46.78, 47.68, 46.7, 46.77, 636264],
    [47.3, 49.89, 46.94, 47.57, 1557580],
    [45.91, 49.45, 45.91, 47.86, 995265],
    [49.3, 50.33, 48.22, 48.7, 1147240],
    [48.1, 49.09, 46.86, 47.04, 920253],
    [45.63, 45.63, 42.7, 45.0, 1075819],
    [46.0, 46.6, 45.35, 46.0, 713216],
    [46.46, 48.26, 46.33, 46.81, 917879],
    [46.6, 47.68, 45.44, 45.73, 653574],
    [45.27, 46.8, 44.79, 45.48, 543064],
    [45.31, 46.46, 44.15, 45.75, 705271],
    [46.03, 46.04, 43.69, 43.75, 621114],
    [44.25, 46.17, 43.93, 45.77, 791233],
    [44.79, 45.17, 44.0, 44.19, 531942],
    [44.66, 44.75, 42.0, 42.03, 772824],
    [40.48, 41.18, 38.67, 39.08, 757104],
    [39.9, 39.97, 38.39, 39.45, 461181],
    [39.9, 41.26, 39.87, 40.64, 533289],
    [40.4, 40.76, 39.24, 39.43, 377749],
    [38.85, 39.94, 38.6, 39.53, 367940],
    [38.66, 39.97, 38.45, 39.84, 338891],
    [39.5, 39.6, 38.55, 38.75, 404005],
    [39.88, 39.89, 39.24, 39.49, 367070],
    [39.53, 39.53, 38.28, 38.48, 328915],
    [38.88, 38.98, 38.02, 38.29, 261706],
    [38.7, 39.06, 38.54, 38.82, 273821],
    [40.05, 41.98, 40.03, 41.98, 713712],
    [41.17, 42.47, 41.05, 41.91, 497533],
    [42.74, 43.79, 42.74, 43.02, 667669],
    [42.5, 43.5, 42.5, 42.85, 443211],
    [43.6, 45.08, 43.3, 44.34, 825500],
    [44.5, 44.7, 43.36, 43.59, 680666],
    [43.6, 44.02, 43.2, 43.73, 442433],
    [43.68, 44.6, 43.25, 43.98, 613942],
    [44.0, 45.0, 43.49, 44.49, 611808],
    [44.5, 44.6, 43.13, 44.03, 465116],
    [43.84, 45.68, 43.77, 45.51, 768614],
    [45.56, 45.78, 44.04, 44.41, 663156],
    [44.82, 45.87, 44.51, 44.89, 700289],
    [46.0, 46.95, 45.55, 46.32, 1021313],
    [46.1, 46.49, 44.76, 45.1, 804400],
    [44.2, 44.63, 43.09, 44.36, 862876],
    [44.65, 45.86, 44.11, 45.56, 1014030],
    [47.86, 49.18, 47.85, 48.2, 1499983],
    [48.9, 49.5, 47.47, 48.76, 1321662],
    [48.0, 51.59, 47.1, 50.75, 1686549],
    [53.8, 55.83, 53.19, 55.83, 1629943],
    [56.76, 56.99, 54.6, 55.24, 2243986],
    [53.51, 57.97, 53.2, 57.49, 2182123],
    [57.79, 57.79, 54.28, 54.31, 1744515],
    [55.17, 59.73, 54.63, 58.05, 2510798],
    [57.47, 61.96, 57.01, 58.33, 2402943],
    [56.91, 61.0, 56.08, 60.81, 2319497],
    [59.78, 66.89, 59.77, 66.22, 2859682],
    [66.88, 71.28, 65.67, 66.84, 3125058],
    [67.05, 73.14, 63.95, 72.88, 2869279],
    [74.0, 80.17, 71.0, 80.17, 2751391],
    [83.0, 88.19, 81.15, 88.19, 3398367],
    [86.5, 94.9, 83.5, 86.16, 3513412],
    [83.01, 87.5, 81.0, 84.74, 2722646],
    [86.0, 89.77, 80.33, 82.05, 3103586],
    [80.1, 83.51, 75.0, 75.65, 2953365],
    [76.86, 77.54, 72.65, 75.35, 2532512],
    [76.23, 82.89, 76.23, 80.13, 3253501],
    [79.0, 81.86, 77.8, 80.08, 2270520],
    [79.66, 80.42, 75.08, 75.65, 2053712],
    [70.92, 73.0, 69.7, 70.3, 1681298],
    [72.98, 75.65, 71.4, 75.29, 1664743],
    [73.5, 76.44, 72.47, 74.05, 1502748],
    [72.6, 74.26, 71.0, 71.95, 1279991],
    [75.0, 75.88, 68.85, 68.92, 1863166],
    [70.3, 74.47, 67.95, 74.32, 1781531],
    [75.06, 78.38, 74.96, 76.93, 1917094],
    [76.0, 82.83, 76.0, 81.76, 2228067],
    [81.89, 85.55, 81.03, 83.03, 2340781],
    [85.88, 91.33, 85.6, 91.33, 2372194],
    [91.96, 91.96, 84.38, 86.09, 2221175],
    [84.0, 94.7, 84.0, 94.7, 2573253],
    [100.0, 104.17, 99.99, 104.17, 1197230],
    [106.92, 109.33, 100.68, 100.89, 3358285],
    [103.0, 105.8, 92.4, 103.25, 3261605],
    [101.29, 105.07, 96.45, 103.55, 2285109]
];

(function () {
    'use strict';

    var REDUCE = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;

    /* css 变量值带空格，统一 trim；切主题时重新取一遍 */
    function cssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }

    /* 把任意 css 颜色转成带 alpha 的 rgba 串，用于 canvas 渐变 */
    var probe = document.createElement('canvas').getContext('2d');
    function rgba(color, a) {
        probe.fillStyle = '#000';
        probe.fillStyle = color;
        var s = probe.fillStyle;
        var m = /^#([0-9a-f]{6})$/i.exec(s);
        if (m) {
            var v = parseInt(m[1], 16);
            return 'rgba(' + ((v >> 16) & 255) + ',' + ((v >> 8) & 255) + ',' + (v & 255) + ',' + a + ')';
        }
        m = /^rgba?\(([^)]+)\)/i.exec(s);
        if (m) {
            var p = m[1].split(',');
            return 'rgba(' + p[0].trim() + ',' + p[1].trim() + ',' + p[2].trim() + ',' + a + ')';
        }
        return color;
    }

    /* =====================================================================
     * 1 · 品牌带背景：真实日K 横向循环滚动
     * ===================================================================== */
    function initKlineCanvas() {
        var cv = document.getElementById('heroCanvas');
        if (!cv || !cv.getContext) return;
        var ctx = cv.getContext('2d');
        if (!ctx || !KC.length) return;

        var RAW = KC;
        var N = RAW.length;                 // 82
        var SERIES = RAW.concat(RAW);       // 铺两遍：滚满 N 根后画面回到原点，无缝
        var VIS = 46;                       // 横向塞得下的格数
        var MS_PER_BAR = 900;               // 每根停留时长

        /* 量程固定：整段最低 38.02 / 最高 109.33，上下各留一点边。
           固定量程才看得出"这是一段真实历史"，不随画面内容缩放。 */
        var LO = 36.0, HI = 111.5;

        var maxVol = 0;
        for (var q = 0; q < N; q++) if (RAW[q][4] > maxVol) maxVol = RAW[q][4];

        var W = 0, H = 0, slot = 0;
        var up = cssVar('--up'), down = cssVar('--down'),
            border = cssVar('--border'), pl = cssVar('--primary-light');
        var fillUp = null, fillDown = null;

        var pos = 0;                        // 已滚过的根数（浮点），在 [0, N) 循环

        function buildGradients() {
            if (!W) return;
            /* 左侧淡右侧浓：品牌带左半边留给文案 */
            function ramp(color, a0, a1, a2, a3) {
                var g = ctx.createLinearGradient(0, 0, W, 0);
                g.addColorStop(0, rgba(color, a0));
                g.addColorStop(0.34, rgba(color, a1));
                g.addColorStop(0.68, rgba(color, a2));
                g.addColorStop(1, rgba(color, a3));
                return g;
            }
            fillUp = ramp(up, 0.06, 0.2, 0.4, 0.6);
            fillDown = ramp(down, 0.06, 0.2, 0.4, 0.6);
        }

        function resize() {
            var r = cv.getBoundingClientRect();
            W = Math.max(1, Math.round(r.width));
            H = Math.max(1, Math.round(r.height));
            var dpr = Math.min(window.devicePixelRatio || 1, 2);
            cv.width = Math.round(W * dpr);
            cv.height = Math.round(H * dpr);
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            slot = W / VIS;
            buildGradients();
        }

        function xAt(i) { return (i - pos) * slot; }

        function draw() {
            if (!W) return;
            var pTop = H * 0.13, pBot = H * 0.70;
            var vTop = H * 0.78, vBot = H * 0.94;
            var span = HI - LO;
            var yOf = function (p) { return pBot - (p - LO) / span * (pBot - pTop); };

            ctx.clearRect(0, 0, W, H);

            var i0 = Math.max(0, Math.floor(pos) - 1);
            var i1 = Math.min(SERIES.length - 1, Math.ceil(pos + VIS) + 1);

            /* --- 网格 --- */
            ctx.lineWidth = 1;
            ctx.strokeStyle = rgba(border, 0.45);
            ctx.beginPath();
            for (var gy = 0; gy <= 4; gy++) {
                var y = Math.round(pTop + (pBot - pTop) * gy / 4) + 0.5;
                ctx.moveTo(0, y); ctx.lineTo(W, y);
            }
            for (var g = Math.floor(pos / 8) * 8; g <= i1; g += 8) {
                var gx = Math.round(xAt(g)) + 0.5;
                if (gx < -1 || gx > W + 1) continue;
                ctx.moveTo(gx, pTop); ctx.lineTo(gx, vBot);
            }
            ctx.stroke();

            /* --- 成交量：开方压缩，小柱子也看得见 --- */
            for (var v = i0; v <= i1; v++) {
                var vx = xAt(v);
                if (vx < -slot || vx > W + slot) continue;
                var k = SERIES[v];
                var vh = Math.sqrt(k[4] / maxVol) * (vBot - vTop);
                ctx.fillStyle = k[3] >= k[0]
                    ? rgba(up, 0.16 + 0.2 * (vx / W))
                    : rgba(down, 0.16 + 0.2 * (vx / W));
                ctx.fillRect(vx - slot * 0.3, vBot - vh, slot * 0.6, vh);
            }

            /* --- 收盘价折线：让走势连贯 --- */
            ctx.beginPath();
            var started = false;
            for (var c = i0; c <= i1; c++) {
                var cx = xAt(c);
                if (cx < -slot || cx > W + slot) continue;
                var cy = yOf(SERIES[c][3]);
                if (!started) { ctx.moveTo(cx, cy); started = true; }
                else ctx.lineTo(cx, cy);
            }
            if (started) {
                ctx.strokeStyle = rgba(pl, 0.4);
                ctx.lineWidth = 1.4;
                ctx.stroke();
            }

            /* --- 蜡烛 --- */
            var bw = Math.max(2, Math.min(slot * 0.62, 15));
            for (var j = i0; j <= i1; j++) {
                var x = xAt(j);
                if (x < -slot || x > W + slot) continue;
                var cd = SERIES[j];
                var rising = cd[3] >= cd[0];
                ctx.fillStyle = rising ? fillUp : fillDown;
                ctx.strokeStyle = rising ? fillUp : fillDown;

                ctx.lineWidth = 1;
                ctx.beginPath();
                ctx.moveTo(Math.round(x) + 0.5, yOf(cd[1]));
                ctx.lineTo(Math.round(x) + 0.5, yOf(cd[2]));
                ctx.stroke();

                var yo = yOf(cd[0]), yc = yOf(cd[3]);
                ctx.fillRect(x - bw / 2, Math.min(yo, yc), bw, Math.max(Math.abs(yc - yo), 1));
            }

            /* --- 当前价虚线 + 光点：跟着画面最右侧那根走，看起来"在跑" --- */
            var li = Math.min(SERIES.length - 1, Math.floor(pos + VIS) - 1);
            if (li >= i0) {
                var lx = xAt(li), ly = yOf(SERIES[li][3]);
                if (lx < W - 2) {
                    ctx.save();
                    ctx.setLineDash([4, 5]);
                    ctx.strokeStyle = rgba(pl, 0.5);
                    ctx.lineWidth = 1;
                    ctx.beginPath();
                    ctx.moveTo(lx, Math.round(ly) + 0.5);
                    ctx.lineTo(W, Math.round(ly) + 0.5);
                    ctx.stroke();
                    ctx.restore();

                    var glow = ctx.createRadialGradient(lx, ly, 0, lx, ly, 9);
                    glow.addColorStop(0, rgba(pl, 0.85));
                    glow.addColorStop(1, rgba(pl, 0));
                    ctx.fillStyle = glow;
                    ctx.beginPath();
                    ctx.arc(lx, ly, 9, 0, Math.PI * 2);
                    ctx.fill();

                    ctx.fillStyle = rgba(pl, 1);
                    ctx.beginPath();
                    ctx.arc(lx, ly, 2.4, 0, Math.PI * 2);
                    ctx.fill();
                }
            }
        }

        /* ---- 生命周期 ---- */
        var raf = 0, lastT = 0, running = false;

        function frame(t) {
            if (!running) return;
            var dt = lastT ? Math.min(t - lastT, 100) : 16;
            lastT = t;
            pos += dt / MS_PER_BAR;
            if (pos >= N) pos -= N;      // 无缝回绕：第 N 根位置 == 第 0 根
            draw();
            raf = requestAnimationFrame(frame);
        }

        function start() {
            if (running || REDUCE) return;
            running = true;
            lastT = 0;
            raf = requestAnimationFrame(frame);
        }

        function stop() {
            running = false;
            if (raf) cancelAnimationFrame(raf);
            raf = 0;
        }

        function refreshColors() {
            up = cssVar('--up'); down = cssVar('--down');
            border = cssVar('--border'); pl = cssVar('--primary-light');
            buildGradients();
            draw();
        }

        /* 静态降级：停在一段有起伏的位置，别选在横盘区 */
        if (REDUCE) pos = 26;

        resize();
        draw();

        window.addEventListener('resize', function () { resize(); draw(); });
        document.addEventListener('visibilitychange', function () {
            if (document.hidden) stop(); else if (inView) start();
        });

        if (window.MutationObserver) {
            new MutationObserver(refreshColors)
                .observe(document.documentElement, {attributes: true, attributeFilter: ['data-theme']});
        }

        /* 滚出视口就停，省点 CPU */
        var inView = true;
        if (window.IntersectionObserver) {
            new IntersectionObserver(function (es) {
                inView = es[0].isIntersecting;
                if (inView && !document.hidden) start(); else stop();
            }, {threshold: 0}).observe(cv);
        }

        if (!REDUCE) start();
    }

    /* =====================================================================
     * 2 · 核心能力轮播
     * ===================================================================== */
    function initShowcase() {
        var root = document.getElementById('showcase');
        var track = document.getElementById('scTrack');
        var dotsBox = document.getElementById('scDots');
        if (!root || !track || !dotsBox) return;

        var slides = Array.prototype.slice.call(track.querySelectorAll('.sc-slide'));
        if (!slides.length) return;

        var n = slides.length;
        var idx = 0;
        var timer = null;
        var DELAY = 6000;

        dotsBox.innerHTML = slides.map(function (_, i) {
            return '<button class="sc-dot" type="button" data-i="' + i + '" aria-label="第 ' + (i + 1) + ' 张"></button>';
        }).join('');
        var dots = Array.prototype.slice.call(dotsBox.querySelectorAll('.sc-dot'));

        function go(i) {
            idx = (i + n) % n;
            track.style.transform = 'translateX(' + (-idx * 100) + '%)';
            slides.forEach(function (s, k) {
                var on = k === idx;
                s.classList.toggle('is-active', on);
                s.setAttribute('aria-hidden', on ? 'false' : 'true');
            });
            dots.forEach(function (d, k) {
                d.classList.toggle('on', k === idx);
                if (k === idx) d.setAttribute('aria-current', 'true');
                else d.removeAttribute('aria-current');
            });
        }

        function play() {
            if (REDUCE) return;
            stop();
            timer = setInterval(function () { go(idx + 1); }, DELAY);
        }
        function stop() {
            if (timer) clearInterval(timer);
            timer = null;
        }

        dotsBox.addEventListener('click', function (e) {
            var b = e.target.closest ? e.target.closest('.sc-dot') : null;
            if (!b) return;
            go(parseInt(b.getAttribute('data-i'), 10));
            play();
        });

        var prev = document.getElementById('scPrev');
        var next = document.getElementById('scNext');
        if (prev) prev.addEventListener('click', function () { go(idx - 1); play(); });
        if (next) next.addEventListener('click', function () { go(idx + 1); play(); });

        root.addEventListener('mouseenter', stop);
        root.addEventListener('mouseleave', play);
        root.addEventListener('focusin', stop);
        root.addEventListener('focusout', function (e) {
            if (!root.contains(e.relatedTarget)) play();
        });

        root.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowLeft') { go(idx - 1); play(); }
            else if (e.key === 'ArrowRight') { go(idx + 1); play(); }
        });

        /* 移动端左右滑动 */
        var sx = 0, sy = 0, tracking = false;
        track.addEventListener('touchstart', function (e) {
            var t = e.changedTouches[0];
            sx = t.clientX; sy = t.clientY; tracking = true;
        }, {passive: true});
        track.addEventListener('touchend', function (e) {
            if (!tracking) return;
            tracking = false;
            var t = e.changedTouches[0];
            var dx = t.clientX - sx, dy = t.clientY - sy;
            if (Math.abs(dx) > 44 && Math.abs(dx) > Math.abs(dy)) {
                go(idx + (dx < 0 ? 1 : -1));
                play();
            }
        }, {passive: true});

        go(0);
        play();
    }

    initKlineCanvas();
    initShowcase();
})();
