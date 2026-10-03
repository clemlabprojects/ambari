import { CATALOG_TEMPLATES, renderTemplate } from '../trinoCatalogTemplates';

describe('trinoCatalogTemplates', () => {
  it('renders the user-chosen templates (postgresql, mysql, s3, hdfs, impala) plus generic', () => {
    expect(CATALOG_TEMPLATES.map(t => t.id)).toEqual(['postgresql', 'mysql', 's3', 'hdfs', 'impala', 'generic']);
    // s3 / hdfs / impala are the Hive connector with a metastore — Trino has no Impala connector
    for (const id of ['s3', 'hdfs', 'impala']) expect(CATALOG_TEMPLATES.find(t => t.id === id)!.connector).toBe('hive');
  });
  it('emits connector.name first, fixed props, then only the filled fields', () => {
    const t = CATALOG_TEMPLATES.find(x => x.id === 'postgresql')!;
    expect(renderTemplate(t, { 'connection-url': 'jdbc:postgresql://db:5432/x', 'connection-user': 'u', 'connection-password': '' }))
      .toBe('connector.name=postgresql\nconnection-url=jdbc:postgresql://db:5432/x\nconnection-user=u');
  });
  it('s3 template carries the file metastore + native S3 and applies field defaults', () => {
    const t = CATALOG_TEMPLATES.find(x => x.id === 's3')!;
    const out = renderTemplate(t, { 'hive.metastore.catalog.dir': 's3://b/w', 's3.region': 'eu-west-1' });
    expect(out.split('\n')).toEqual(['connector.name=hive', 'hive.metastore=file', 'fs.native-s3.enabled=true', 'hive.metastore.catalog.dir=s3://b/w', 's3.region=eu-west-1', 's3.path-style-access=true']);
  });
  it('generic template renders nothing by itself (the operator writes the properties)', () => {
    expect(renderTemplate(CATALOG_TEMPLATES.find(x => x.id === 'generic')!, {})).toBe('');
  });
});
