// Simple “required” rule
export const required = (msg: string) => ({ required: true, message: msg });

// Built-in URL validator (AntD) with custom message
export const url = (msg = "Enter a valid URL") => ({ type: "url", message: msg });

// Slug (lowercase letters, numbers, dashes)
export const slug = {
  validator(_: any, v?: string) {
    if (!v) return Promise.resolve();                     // let "required" handle empty
    return /^[a-z0-9-]+$/.test(v)
      ? Promise.resolve()
      : Promise.reject(new Error("Use lowercase letters, numbers, and dashes only"));
  },
};

// Cross-field matcher (when you need getFieldValue)
export const matchField = (otherName: string, msg: string) =>
  ({ getFieldValue }: any) => ({
    validator(_: any, v?: any) {
      if (!v) return Promise.resolve();
      return getFieldValue(otherName) === v
        ? Promise.resolve()
        : Promise.reject(new Error(msg));
    },
  });

// Optional: trim input before storing in Form
export const trim = (e: any) => e?.target?.value?.trim?.();
