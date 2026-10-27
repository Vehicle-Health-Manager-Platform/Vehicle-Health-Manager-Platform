"""Fixed Java exec with parent-death binding; no user paths, flags or class names."""
import os
import sys
sys.path.insert(0,'/app')
from process_limits import guard_parent

if __name__=='__main__':
    if len(sys.argv)!=1:raise SystemExit(2)
    guard_parent()
    for name in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'):os.environ.pop(name,None)
    os.execv('/opt/java/openjdk/bin/java',['java','-Xms32m','-Xmx256m','-XX:ActiveProcessorCount=2',
        '-Djava.awt.headless=true','-Djava.io.tmpdir=/tmp','-Duser.home=/tmp','-cp',
        '/document/java/classes:/document/java/lib/*','com.autocare.platform.recognition.DocumentPdfMain'])
