import { defineConfig } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  server: { proxy: { '/api': 'http://localhost:3000' } },
  build: { chunkSizeWarningLimit: 2500 },
  plugins: [VitePWA({
    registerType: 'autoUpdate',
    includeAssets: ['icons/icon.svg', 'icons/apple-touch-icon.png'],
    manifest: {
      name: 'PPCI — Levantamento', short_name: 'PPCI', description: 'Levantamento de PPCI em campo sobre plantas PDF',
      lang: 'pt-BR', start_url: '/', scope: '/', display: 'standalone', orientation: 'any', background_color: '#111827', theme_color: '#111827',
      icons: [{ src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' }, { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
        { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' }],
    },
    workbox: {
      globPatterns: ['**/*.{js,css,html,svg,png,woff2}'], maximumFileSizeToCacheInBytes: 6 * 1024 * 1024,
      navigateFallback: '/index.html', navigateFallbackDenylist: [/^\/api\//],
      runtimeCaching: [{ urlPattern: /\/pdfjs\//, handler: 'CacheFirst', options: { cacheName: 'pdfjs-fonts', expiration: { maxEntries: 80 } } }],
    },
  })],
});
