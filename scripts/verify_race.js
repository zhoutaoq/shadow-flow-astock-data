const fs = require('fs');
const vm = require('vm');

// 模拟浏览器真实时序：common.js 注册 DOMContentLoaded -> 内联脚本先跑(发起 loadMode fetch)
// -> DOMContentLoaded 触发(此时 fetch 可能还没回来) -> renderNav
async function scenario(noDb) {
  const src = fs.readFileSync('target/classes/static/js/common.js', 'utf8');
  const html = fs.readFileSync('src/main/resources/static/index.html', 'utf8');
  const inline = html.match(/<script>([\s\S]*?)<\/script>/)[1];

  const domListeners = {};
  const store = {};
  function el(id) {
    return {
      id, style: {}, _html: '', _text: '',
      set innerHTML(v) { this._html = v; store[id] = v; },
      get innerHTML() { return this._html; },
      set textContent(v) { this._text = v; },
      get textContent() { return this._text; },
      addEventListener() {}
    };
  }
  const sb = {};
  sb.CustomEvent = function () {};
  sb.location = { pathname: '/' };
  // 模拟网络延迟，让 fetch 一定晚于 DOMContentLoaded 返回
  sb.fetch = async (url) => {
    await new Promise(r => setTimeout(r, 30));
    if (String(url).includes('/api/mode')) {
      return { json: async () => ({ success: true, data: { dbEnabled: !noDb, maxSpanDays: 1200, tokenFromRequest: true } }) };
    }
    return { json: async () => ({ success: true, data: { stockCount: 1, klineCount: 2, coveredStockCount: 3 } }) };
  };
  sb.document = {
    cookie: 'waizao_token=test', // 跳过令牌弹窗（非本次验证对象）
    getElementById: (id) => el(id),
    querySelector: (sel) => (sel === '.quick' ? el('quick') : el(sel)),
    addEventListener: (name, fn) => { (domListeners[name] = domListeners[name] || []).push(fn); },
    dispatchEvent: () => {},
    body: { appendChild: () => {} }
  };
  vm.createContext(sb);
  vm.runInContext(src, sb);          // 注册 DOMContentLoaded
  vm.runInContext(inline, sb);       // 内联脚本：先发起 loadMode
  // 触发 DOMContentLoaded 并等它跑完
  for (const fn of domListeners['DOMContentLoaded'] || []) {
    await fn();
  }
  await new Promise(r => setTimeout(r, 50)); // 让内联 IIFE 的后续也落地

  const nav = store['nav'] || '';
  const quick = store['quick'] || '';
  const navTexts = [...nav.matchAll(/class="nav-item[^"]*"[^>]*>([^<]+)</g)].map(m => m[1]);
  const quickTexts = [...quick.matchAll(/<b>([^<]+)<\/b>/g)].map(x => x[1]);
  return { navTexts, quickTexts, group: /nav-group/.test(nav) };
}

(async () => {
  console.log('== 场景A：无数据库模式（真实事件时序，含竞态窗口）==');
  const a = await scenario(true);
  const okA = a.navTexts.length === 5 && !a.group && a.quickTexts.length === 5;
  console.log('导航栏: ' + JSON.stringify(a.navTexts) + ' | 有分组=' + (a.group ? '是' : '否'));
  console.log('快速入口: ' + JSON.stringify(a.quickTexts));
  console.log(okA ? 'PASS' : 'FAIL');

  console.log('== 场景B：数据库模式（菜单应保持原样分组）==');
  const b = await scenario(false);
  const okB = b.group && ['概览', '分析回测', '数据更新', '系统'].every(t => b.navTexts.includes(t));
  console.log('导航栏: ' + JSON.stringify(b.navTexts) + ' | 有分组=' + (b.group ? '是' : '否'));
  console.log(okB ? 'PASS' : 'FAIL');

  console.log(okA && okB ? '\n总体：全部 PASS' : '\n总体：存在 FAIL');
})();
