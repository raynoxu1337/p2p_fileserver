// Bundles src/ into p2p_resolver/resolver.js so the page is plain static
// files (upload the p2p_resolver folder to Neocities / GitHub Pages as is).
import { build } from 'esbuild'

await build({
  entryPoints: ['src/main.js'],
  outfile: 'p2p_resolver/resolver.js',
  bundle: true,
  format: 'esm',
  platform: 'browser',
  target: ['chrome110', 'firefox115', 'safari16.4'],
  minify: true,
  sourcemap: false,
  legalComments: 'none',
  define: { 'process.env.NODE_ENV': '"production"', global: 'globalThis' },
  logLevel: 'info'
})
