var pfHook = function () {
  return 'GYun';
};
var uaHook = function () {
  return 'Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/62.0.3202.75 Safari/537.36';
};
try {
  Object.defineProperty(navigator, 'platform',
      {get: pfHook, configurable: true});
  Object.defineProperty(navigator, 'userAgent',
      {get: uaHook, configurable: true});
} catch (e) {
  try {
    navigator.__defineGetter__('platform', pfHook);
    navigator.__defineGetter__('userAgent', uaHook);
  } catch (e) {

  }
}
var hookSupport = navigator.platform == pfHook();
if (!hookSupport) {
  alert('proxyee-down百度云下载插件加载失败，浏览器不支持请换其它浏览器');
} else {
  var initHookInterval = setInterval(function () {
    try {
      if ($('.module-header-wrapper dl:first').find('dd:first').length == 0) {
        return;
      }
      clearInterval(initHookInterval);
    } catch (e) {
      return;
    }
    var pd_dd = $('.module-header-wrapper dl:first').find('dd:first');
    if (pd_dd.find(">span").attr("class") && pd_dd.find(">span>span").attr(
            "class")) {
      var pd_parent_span_class = pd_dd.find(">span").attr("class").split(
          " ")[0];
      var pd_child_span_class = pd_dd.find(">span>span").attr("class").split(
          " ")[0];
      $('.module-header-wrapper dl:first').find('dd:first').append(
          '<span class="' + pd_parent_span_class + ' find-light">'
          + '<a href="https://github.com/monkeyWie/proxyee-down" target="_blank" title="百度云下载插件加载成功">proxyee-down</a>'
          + '<span class="' + pd_child_span_class + '"></span>'
          + '<i class="find-light-icon"></i>'
          + '</span>');
    }
  }, 200);
}
