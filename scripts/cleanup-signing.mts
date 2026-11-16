import {rmSync} from 'node:fs';
import path from 'node:path';
const temporary=process.env.RUNNER_TEMP;
if(!temporary||!path.isAbsolute(temporary))throw Error('Absolute runner temporary directory required');
rmSync(path.join(temporary,'cbgpu-release.jks'),{force:true});
rmSync('android/key.properties',{force:true});
