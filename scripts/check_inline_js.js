/*
 * 抽取各页面内联 <script>（无 src 属性）做语法检查，避免"页面白屏但后端没报错"。
 *
 * 用法（项目根目录执行）：
 *   node scripts/check_inline_js.js src/main/resources/static
 *
 * 无第三方依赖，只要 node。
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const base = process.argv[2];
const files = [];
(function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    if (fs.statSync(p).isDirectory()) walk(p);
    else if (name.endsWith('.html')) files.push(p);
  }
})(base);

let bad = 0;
for (const f of files) {
  const html = fs.readFileSync(f, 'utf8');
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script>/g;
  let m, i = 0;
  while ((m = re.exec(html)) !== null) {
    if (/\bsrc\s*=/.test(m[1])) continue;
    i++;
    const tmp = path.join(require('os').tmpdir(), `chk_${path.basename(f, '.html')}_${i}.mjs`);
    fs.writeFileSync(tmp, m[2], 'utf8');
    try {
      execFileSync(process.execPath, ['--check', tmp], { stdio: 'pipe' });
      console.log(`OK   ${path.relative(base, f)} #${i}`);
    } catch (e) {
      bad++;
      console.log(`FAIL ${path.relative(base, f)} #${i}\n${e.stderr.toString().split('\n').slice(0, 6).join('\n')}`);
    }
    fs.unlinkSync(tmp);
  }
}
console.log(bad === 0 ? '\n全部内联 JS 语法通过' : `\n${bad} 处语法错误`);
process.exit(bad === 0 ? 0 : 1);
