// Re-exports everything from chartConfig.jsx
// This shim exists so imports of 'chartConfig' (without extension) continue to work.
// The actual source is chartConfig.jsx — JSX cannot live in a .js file.
export * from './chartConfig.jsx';
