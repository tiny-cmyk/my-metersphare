const debug = import.meta.env.MODE !== 'production';

export default debug;

/**
 * Echo 子应用部署根路径（以 / 结尾），来源于 vite 的 base 配置。
 * 例如部署在 /my-metersphare/ 下时为 '/my-metersphare/'，根路径部署时为 '/'。
 */
export const APP_BASE = import.meta.env.BASE_URL || '/';

/**
 * 去掉末尾斜杠的部署根路径，例如 '/my-metersphare' 或空字符串 ''。
 * 用于拼接到 window.location.origin 后面。
 */
export const APP_BASE_NO_SLASH = APP_BASE.replace(/\/$/, '');

/**
 * 当前页面在浏览器中的根 URL（含 origin + base 前缀，不带尾部斜杠）。
 * 用于代替散落在代码里的 `window.location.origin`，保证在 Echo 子应用模式
 * 下生成的链接（含 hash 路由的 #/foo）依然能正确落到本应用。
 */
export function siteBaseHref(): string {
  return `${window.location.origin}${APP_BASE_NO_SLASH}`;
}
