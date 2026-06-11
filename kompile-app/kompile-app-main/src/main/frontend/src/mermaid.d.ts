/**
 * Type declarations for mermaid (loaded dynamically at runtime).
 * If mermaid is not installed, this provides the minimal type stubs needed.
 */
declare module 'mermaid' {
  const mermaid: {
    initialize(config: Record<string, any>): void;
    render(id: string, text: string): Promise<{ svg: string; bindFunctions?: (element: Element) => void }>;
    parse(text: string): Promise<boolean>;
  };
  export default mermaid;
}
