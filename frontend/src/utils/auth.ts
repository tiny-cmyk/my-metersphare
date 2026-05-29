import { isLogin as isLoginFun } from '@/api/modules/user';
import { WHITE_LIST_NAME } from '@/router/constants';

const SESSION_ID = 'sessionId';
const CSRF_TOKEN = 'csrfToken';
const LOGIN_TYPE = 'loginType';

const isLogin = async () => {
  try {
    await isLoginFun();
    return true;
  } catch (err) {
    return false;
  }
};
// 获取token
const getToken = () => {
  return { [SESSION_ID]: localStorage.getItem(SESSION_ID), [CSRF_TOKEN]: localStorage.getItem(CSRF_TOKEN) || '' };
};

const setToken = (sessionId: string, csrfToken: string) => {
  localStorage.setItem(SESSION_ID, sessionId);
  localStorage.setItem(CSRF_TOKEN, csrfToken);
};

const setLongType = (loginType: string) => {
  localStorage.setItem(LOGIN_TYPE, loginType);
};

const getLongType = () => {
  return localStorage.getItem(LOGIN_TYPE);
};

const clearToken = () => {
  localStorage.removeItem(SESSION_ID);
  localStorage.removeItem(CSRF_TOKEN);
};

const hasToken = (name: string) => {
  if (WHITE_LIST_NAME.includes(name)) {
    return true;
  }
  return !!localStorage.getItem(SESSION_ID) && !!localStorage.getItem(CSRF_TOKEN);
};

const isEmbeddedInEcho = () => {
  try {
    return window.self !== window.top;
  } catch {
    return true;
  }
};

// 独立访问 metersphare 且没有上游 sid 时，跳到 ScriptPlatform 的 Google OAuth 入口走完登录再回来
const redirectToScriptPlatformLogin = () => {
  const returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  const target = `/scriptPlatform/auth/google/start?return_to=${encodeURIComponent(returnTo || '/')}`;
  window.location.href = target;
};

const setLoginExpires = () => {
  localStorage.setItem('loginExpires', Date.now().toString());
};

const isLoginExpires = () => {
  const lastLoginTime = Number(localStorage.getItem('loginExpires'));
  const now = Date.now();
  const diff = now - lastLoginTime;
  const thirtyDay = 24 * 60 * 60 * 1000 * 30;
  return diff > thirtyDay;
};

export {
  clearToken,
  getLongType,
  getToken,
  hasToken,
  isEmbeddedInEcho,
  isLogin,
  isLoginExpires,
  redirectToScriptPlatformLogin,
  setLoginExpires,
  setLongType,
  setToken,
};
