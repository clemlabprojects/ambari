declare module 'lodash.debounce' {
  import { DebounceSettings } from 'lodash';
  
  interface DebouncedFunc<T extends (...args: any[]) => any> {
    (...args: Parameters<T>): ReturnType<T> | undefined;
    cancel(): void;
    flush(): ReturnType<T> | undefined;
  }
  
  function debounce<T extends (...args: any[]) => any>(
    func: T,
    wait?: number,
    options?: DebounceSettings
  ): DebouncedFunc<T>;
  
  export = debounce;
}

