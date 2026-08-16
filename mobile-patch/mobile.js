/**
 * 移动端软键盘适配（前端插件）
 * 横屏全屏下软键盘会盖住底部输入栏；本脚本用 VisualViewport API 监听键盘弹出，
 * 把整个 App 容器向上平移键盘高度，让输入栏始终可见。
 * 不依赖 DSH 前端内部类名，通用且对 position:fixed 的底部输入栏同样生效。
 */
(function () {
  if (!window.visualViewport) return;
  var vv = window.visualViewport;
  var app = document.getElementById('root') || document.body;
  var lastKb = 0;

  function computeKb() {
    // 键盘高度 ≈ 布局视口高度 - 视觉视口高度 - 视觉视口顶部偏移
    var kb = window.innerHeight - vv.height - vv.offsetTop;
    return Math.max(0, Math.round(kb));
  }

  function apply() {
    var kb = computeKb();
    if (Math.abs(kb - lastKb) < 6) return;
    lastKb = kb;
    document.documentElement.style.setProperty('--kb-height', kb + 'px');
    if (kb > 120) {
      // 键盘弹出：把 App 容器向上平移，露出底部输入栏
      app.style.transform = 'translateY(' + (-kb) + 'px)';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.add('kb-open');
    } else {
      // 键盘收起：恢复原位
      app.style.transform = '';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.remove('kb-open');
    }
  }

  vv.addEventListener('resize', apply);
  vv.addEventListener('scroll', apply);
  window.addEventListener('resize', apply);
  window.addEventListener('orientationchange', apply);
  document.addEventListener('focusin', function () {
    // 聚焦输入框后延迟一点再算，等键盘完全弹出
    setTimeout(apply, 150);
  });
  document.addEventListener('focusout', function () {
    setTimeout(apply, 150);
  });

  apply();
})();
