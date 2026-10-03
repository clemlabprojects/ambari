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
/**
 * Connector templates for the live Trino catalog editor. Each template is a small form that
 * renders to ordinary catalog .properties text (what Trino and the chart's `catalogs` map use), so
 * the operator never has to remember property names for the common cases. "s3", "hdfs" and
 * "impala" are all the Hive connector with a metastore: Trino has no Impala connector — Impala
 * tables are Hive Metastore tables, so the CDP metastore is what you point at.
 */
export interface TemplateField {
  key: string;            // property name
  label: string;
  placeholder?: string;
  required?: boolean;
  secret?: boolean;       // suggest ${ENV:VAR} instead of a literal
  help?: string;
  defaultValue?: string;
}
export interface CatalogTemplate {
  id: string;
  label: string;
  connector: string;
  description: string;
  fixed?: Record<string, string>;   // properties always emitted
  fields: TemplateField[];
}

export const CATALOG_TEMPLATES: CatalogTemplate[] = [
  {
    id: 'postgresql', label: 'PostgreSQL', connector: 'postgresql',
    description: 'Query a PostgreSQL database.',
    fields: [
      { key: 'connection-url', label: 'JDBC URL', placeholder: 'jdbc:postgresql://db.example.com:5432/analytics', required: true },
      { key: 'connection-user', label: 'User', required: true },
      { key: 'connection-password', label: 'Password', secret: true, placeholder: '${ENV:PG_PASSWORD}', help: 'Prefer a Secret exposed as an env var and reference it as ${ENV:VAR}.' },
    ],
  },
  {
    id: 'mysql', label: 'MySQL / MariaDB', connector: 'mysql',
    description: 'Query a MySQL or MariaDB database.',
    fields: [
      { key: 'connection-url', label: 'JDBC URL', placeholder: 'jdbc:mysql://db.example.com:3306', required: true },
      { key: 'connection-user', label: 'User', required: true },
      { key: 'connection-password', label: 'Password', secret: true, placeholder: '${ENV:MYSQL_PASSWORD}' },
    ],
  },
  {
    id: 's3', label: 'S3 object storage (Hive tables, file metastore)', connector: 'hive',
    description: 'Tables stored on S3 or any S3-compatible store, with the metastore kept as files in the bucket (no Hive Metastore service needed).',
    fixed: { 'hive.metastore': 'file', 'fs.native-s3.enabled': 'true' },
    fields: [
      { key: 'hive.metastore.catalog.dir', label: 'Metastore directory', placeholder: 's3://my-bucket/warehouse', required: true, help: 'Where table metadata is kept; schemas and tables created through Trino land under it.' },
      { key: 's3.endpoint', label: 'S3 endpoint', placeholder: 'https://s3.example.com (leave empty for AWS)' },
      { key: 's3.region', label: 'Region', placeholder: 'us-east-1', required: true },
      { key: 's3.path-style-access', label: 'Path-style access', defaultValue: 'true', help: 'true for MinIO/Ozone-style endpoints, false for AWS virtual-host style.' },
      { key: 's3.aws-access-key', label: 'Access key', secret: true, placeholder: '${ENV:S3_ACCESS_KEY}' },
      { key: 's3.aws-secret-key', label: 'Secret key', secret: true, placeholder: '${ENV:S3_SECRET_KEY}' },
    ],
  },
  {
    id: 'hdfs', label: 'HDFS cluster (remote Hive Metastore)', connector: 'hive',
    description: 'Another Hadoop cluster\'s Hive tables on HDFS, through its Hive Metastore. Kerberos fields are optional.',
    fixed: { 'fs.hadoop.enabled': 'true' },
    fields: [
      { key: 'hive.metastore.uri', label: 'Metastore URI', placeholder: 'thrift://hms.other-cluster:9083', required: true },
      { key: 'hive.config.resources', label: 'Hadoop config files', placeholder: '/etc/trino/hadoop-other/core-site.xml,/etc/trino/hadoop-other/hdfs-site.xml', help: 'core-site/hdfs-site of THAT cluster, mounted into the Trino pods (a ConfigMap mount on the release).' },
      { key: 'hive.metastore.authentication.type', label: 'Metastore auth', placeholder: 'NONE or KERBEROS' },
      { key: 'hive.metastore.service.principal', label: 'Metastore service principal', placeholder: 'hive/_HOST@REALM' },
      { key: 'hive.metastore.client.principal', label: 'Client principal', placeholder: 'trino@REALM' },
      { key: 'hive.metastore.client.keytab', label: 'Client keytab', placeholder: '/etc/security/keytabs/trino.keytab' },
      { key: 'hive.hdfs.authentication.type', label: 'HDFS auth', placeholder: 'NONE or KERBEROS' },
      { key: 'hive.hdfs.trino.principal', label: 'HDFS principal', placeholder: 'trino@REALM' },
      { key: 'hive.hdfs.trino.keytab', label: 'HDFS keytab', placeholder: '/etc/security/keytabs/trino.keytab' },
    ],
  },
  {
    id: 'impala', label: 'Impala / CDP tables (Hive Metastore)', connector: 'hive',
    description: 'Tables managed by Impala on a CDP cluster. Trino reads them through the CDP Hive Metastore (Impala has no connector of its own; its tables are Hive Metastore tables).',
    fixed: { 'fs.hadoop.enabled': 'true', 'hive.metastore.authentication.type': 'KERBEROS', 'hive.hdfs.authentication.type': 'KERBEROS', 'hive.hdfs.impersonation.enabled': 'true' },
    fields: [
      { key: 'hive.metastore.uri', label: 'CDP Metastore URI', placeholder: 'thrift://cdp-master:9083', required: true },
      { key: 'hive.config.resources', label: 'CDP Hadoop config files', placeholder: '/etc/trino/hadoop-cdp/core-site.xml,/etc/trino/hadoop-cdp/hdfs-site.xml', required: true },
      { key: 'hive.metastore.service.principal', label: 'Metastore service principal', placeholder: 'hive/_HOST@CDP.REALM', required: true },
      { key: 'hive.metastore.client.principal', label: 'Trino principal', placeholder: 'trino/coordinator@CDP.REALM', required: true },
      { key: 'hive.metastore.client.keytab', label: 'Trino keytab', placeholder: '/etc/security/keytabs/trino-cdp.keytab', required: true },
      { key: 'hive.hdfs.trino.principal', label: 'HDFS principal', placeholder: 'trino/coordinator@CDP.REALM', required: true },
      { key: 'hive.hdfs.trino.keytab', label: 'HDFS keytab', placeholder: '/etc/security/keytabs/trino-cdp.keytab', required: true },
    ],
  },
  {
    id: 'generic', label: 'Other connector (raw properties)', connector: '',
    description: 'Any Trino connector: write the .properties lines yourself (connector.name required).',
    fields: [],
  },
];

/** Render a template + field values into catalog .properties text (pure; unit-tested). */
export function renderTemplate(t: CatalogTemplate, values: Record<string, string | undefined>): string {
  const lines: string[] = [];
  if (t.connector) lines.push(`connector.name=${t.connector}`);
  for (const [k, v] of Object.entries(t.fixed || {})) lines.push(`${k}=${v}`);
  for (const f of t.fields) {
    const v = (values[f.key] ?? f.defaultValue ?? '').toString().trim();
    if (v) lines.push(`${f.key}=${v}`);
  }
  return lines.join('\n');
}
