import * as fs from 'fs'; import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';
const def = JSON.parse(fs.readFileSync(path.join(__dirname,'../../../../../KDPS/services/SUPERSET/service.json'),'utf8'));
const VARS = (def.variables||[]).filter((v:any)=>['dynamicRoleRaw','dynamicRoleOn','dynamicRolePattern'].includes(v.name));
const B = (def.bindings||[]).filter((b:any)=>b.name==='superset-dynamic-role-mapping');
function resolve(form:any){ const vc=buildVarContext(VARS as any,form,{},{}); const m:any={}; applyBindingTargets(m,B as any,{},form,'superset',vc); return m; }
describe('superset-dynamic-role-mapping binding',()=>{
  it('toggle OFF -> nothing written',()=>{
    const m=resolve({security:{dynamicRoleFromGroup:false}});
    expect(m.global?.security?.dynamicRoleFromGroup).toBeUndefined();
  });
  it('toggle ON -> dynamicRoleFromGroup truthy, no pattern when blank',()=>{
    const m=resolve({security:{dynamicRoleFromGroup:true}});
    expect([true,'true']).toContain(m.global?.security?.dynamicRoleFromGroup);
    expect(m.global?.security?.dynamicRolePattern).toBeUndefined();
  });
  it('toggle ON + pattern -> both written',()=>{
    const m=resolve({security:{dynamicRoleFromGroup:true,dynamicRolePattern:'^ad_'}});
    expect([true,'true']).toContain(m.global?.security?.dynamicRoleFromGroup);
    expect(m.global?.security?.dynamicRolePattern).toBe('^ad_');
  });
});
