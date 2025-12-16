declare module 'js-untar' {
  export interface TarEntry {
    name: string;
    buffer: ArrayBuffer;
    mode?: number;
    uid?: number;
    gid?: number;
    size?: number;
    mtime?: number;
    type?: string;
    linkname?: string;
    ustar?: string;
    version?: string;
    uname?: string;
    gname?: string;
    devmajor?: number;
    devminor?: number;
    prefix?: string;
  }
  
  export function untar(arrayBuffer: ArrayBuffer): Promise<TarEntry[]>;
}

