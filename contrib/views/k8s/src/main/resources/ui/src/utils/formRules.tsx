/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { Rule } from "antd/lib/form";

// Simple “required” rule
export const required = (msg: string) => ({ required: true, message: msg });

// Built-in URL validator (AntD) with custom message
export type AntdRule = Rule;
export type AntdValidator = NonNullable<Rule["validator"]>;
export const domain = (msg = "Enter a valid domain (e.g. example.com)") => ({
  validator(_: any, v?: string) {
    console.log("Validating domain: ", v);
    if (!v) return Promise.resolve(); // let "required" handle empty
    const re = /^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\.(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*\.[A-Za-z]{2,}$/;
    return re.test(v) ? Promise.resolve() : Promise.reject(new Error(msg));
  },
});

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
