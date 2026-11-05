"""Check virtual-location gates against the pinned Chromium Java sources."""
from pathlib import Path
import argparse, json, re, subprocess

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--native',type=Path)
args=parser.parse_args()
native=args.native or Path(__file__).resolve().parents[1]
source=args.source.resolve(strict=True)
output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
if str(output).lower().startswith(('c:', '/mnt/c/')):raise SystemExit('Use a project cache outside C')
lock=json.loads((native/'upstream-lock.json').read_text())
paths=['components/location/android/java/src/org/chromium/components/location/LocationUtils.java',
       'components/location/android/java/src/org/chromium/components/location/LocationSettings.java',
       'components/permissions/android/java/src/org/chromium/components/permissions/PermissionUtil.java']
script="""import fs from'node:fs';import{pathToFileURL}from'node:url';
const[native,input,target,hash]=process.argv.slice(1);
const{preparePatchedFile}=await import(pathToFileURL(native+'/scripts/overlay-plan.mjs'));
process.stdout.write(preparePatchedFile(target,fs.readFileSync(input),hash).content);"""
patched=[]
node=source/'third_party/node/linux/node-linux-x64/bin/node'
for index,name in enumerate(paths):
    baseline=subprocess.check_output(['git','show',lock['revision']+':'+name],cwd=source)
    path=output/('original-'+str(index)+'.txt');path.write_bytes(baseline)
    patched.append(subprocess.check_output([str(node),'--input-type=module','-e',script,
        str(native),str(path),name,lock['files'][name]],text=True))
policy=patched[0].split('    public static boolean usesVirtualLocation() {',1)[1].split('    private boolean hasPermission',1)[0]
policy='    public static boolean usesVirtualLocation() {'+policy
wrappers=[]
for method in ['hasAndroidLocationPermission','hasAndroidFineLocationPermission','isSystemLocationSettingEnabled']:
    match=re.search(r'    private static boolean '+method+r'\(\) \{.*?\n    \}',patched[1],re.S)
    assert match;wrappers.append(match.group().replace('private static','public static'))
for method in ['getRequiredAndroidPermissionsForContentSetting','getOptionalAndroidPermissionsForContentSetting']:
    prefix=patched[2].split('public static String[] '+method,1)[1].split('switch (contentSettingType)',1)[0]
    assert 'LocationUtils.usesVirtualLocation()' in prefix and 'return EMPTY_PERMISSIONS;' in prefix
# The wrapper test compiles the exact helper and JNI entry bodies with sensor-access sentinels.
command=output/'CommandLine.java'
command.write_text('''package org.chromium.base;
public class CommandLine {
 public static boolean initialized=true;
 public static String mode,position;
 public static boolean isInitialized(){return initialized;}
 public static CommandLine getInstance(){return new CommandLine();}
 public String getSwitchValue(String name){return name.endsWith("-mode")?mode:position;}
}''')
fixture=output/'VirtualLocationTest.java'
fixture.write_text('''import org.chromium.base.CommandLine;
class LocationUtils {
 static int sensorChecks;
 static LocationUtils getInstance(){return new LocationUtils();}
 boolean hasAndroidLocationPermission(){sensorChecks++;return false;}
 boolean hasAndroidFineLocationPermission(){sensorChecks++;return false;}
 boolean isSystemLocationSettingEnabled(){sensorChecks++;return false;}
'''+policy+'''}
class Settings {
'''+ '\n'.join(wrappers)+'''
}
public class VirtualLocationTest {
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError();}
 public static void main(String[] args){
  CommandLine.mode="custom";CommandLine.position="38.58,-121.49,100";
  check(LocationUtils.usesVirtualLocation());
  check(Settings.hasAndroidLocationPermission());
  check(Settings.hasAndroidFineLocationPermission());
  check(Settings.isSystemLocationSettingEnabled());
  check(LocationUtils.sensorChecks==0);
  for(String invalid:new String[]{"", "1,2", "1,2,3,4", "NaN,0,100", "0,Infinity,100",
      "91,0,100", "0,-181,100", "0,0,0", "0,0,1000001", "bad,0,100"}){
   CommandLine.position=invalid;check(!LocationUtils.usesVirtualLocation());
  }
  CommandLine.position=null;check(!LocationUtils.usesVirtualLocation());
  CommandLine.position="0,0,100";
  for(String mode:new String[]{"system","blocked","exit-ip","CUSTOM",""}){
   CommandLine.mode=mode;check(!LocationUtils.usesVirtualLocation());
  }
  check(!Settings.hasAndroidLocationPermission());
  check(!Settings.hasAndroidFineLocationPermission());
  check(!Settings.isSystemLocationSettingEnabled());
  check(LocationUtils.sensorChecks==3);
  CommandLine.mode="custom";CommandLine.initialized=false;
  check(!LocationUtils.usesVirtualLocation());
  System.out.println("{\\"passed\\":true,\\"checks\\":"+checks+"}");
 }
}''')
jdk=source/'third_party/jdk/current/bin'
subprocess.run([str(jdk/'javac'),'--release','11','-d',str(output),str(command),str(fixture)],check=True)
result=subprocess.check_output([str(jdk/'java'),'-cp',str(output),'VirtualLocationTest'],text=True)
(output/'result.json').write_text(result)
print(result)
