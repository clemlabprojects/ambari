declare module 'pako' {
  export function deflate(data: Uint8Array | string, options?: any): Uint8Array;
  export function inflate(data: Uint8Array | string, options?: any): Uint8Array;
  export function gzip(data: Uint8Array | string, options?: any): Uint8Array;
  export function ungzip(data: Uint8Array | string, options?: any): Uint8Array;
  
  export const enum FlushValues {
    Z_NO_FLUSH = 0,
    Z_PARTIAL_FLUSH = 1,
    Z_SYNC_FLUSH = 2,
    Z_FULL_FLUSH = 3,
    Z_FINISH = 4,
    Z_BLOCK = 5,
    Z_TREES = 6,
  }
}

