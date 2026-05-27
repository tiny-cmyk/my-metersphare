import baseConfig from './vite.config.base';
import dotenv from 'dotenv';
import { mergeConfig } from 'vite';
import eslint from 'vite-plugin-eslint';

// 注入本地/开发配置环境变量(先导入的配置优先级高)
dotenv.config({ path: ['.env.development.local', '.env.development'] });

export default mergeConfig(
  {
    mode: 'development',
    server: {
      // 远程 systemd 部署没有 xdg-open，强制关闭浏览器自动打开（否则 vite 会持续崩溃重启）
      open: false,
      // 允许被 Echo / Traefik 通过任意 Host 反代访问
      allowedHosts: true,
      fs: {
        strict: true,
      },
      proxy: {
        // 接入 Echo 子应用前缀：浏览器请求 /my-metersphare/front/... → 后端 /...
        '/my-metersphare/ws': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/my-metersphare\/front\/ws/, ''),
          ws: true,
        },
        '/my-metersphare/front': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/my-metersphare\/front/, ''),
        },
        // 旧的无前缀代理（保留兼容旧的开发调用路径）
        '/ws': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front\/ws/, ''),
          ws: true,
        },
        '/front': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front/, ''),
        },
        '/file': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front\/file/, ''),
        },
        '/attachment': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front\/attachment/, ''),
        },
        '/bug/attachment': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front\/bug\/attachment/, ''),
        },
        '/test-plan/report': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front\/test-plan\/report/, ''),
        },
        '/organization': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front\/organization/, ''),
        },
        '/project': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front\/project/, ''),
        },
        '/plugin/image': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front\/plugin\/image/, ''),
        },
        '/base-display': {
          target: process.env.VITE_DEV_DOMAIN,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/front\/base-display/, ''),
        },
      },
    },
    plugins: [
      eslint({
        cache: false,
        include: ['src/**/*.ts', 'src/**/*.tsx', 'src/**/*.vue'],
        exclude: ['node_modules'],
      }),
    ],
  },
  baseConfig
);
