import fs from 'node:fs';import path from 'node:path';import ts from 'typescript';
const output=process.argv[2];if(!output||!path.isAbsolute(output))throw Error('Provide an absolute generated-assets directory.');
const source=fs.readFileSync(new URL('../native-ui/bridge.ts',import.meta.url),'utf8');
const result=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.None,removeComments:true},reportDiagnostics:true});
if(result.diagnostics?.some(d=>d.category===ts.DiagnosticCategory.Error))throw Error('Native bridge TypeScript compilation failed.');
fs.mkdirSync(output,{recursive:true});fs.writeFileSync(path.join(output,'native-bridge.js'),result.outputText);
