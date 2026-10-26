"""OS-owned worker lifetime. Windows job memory/process caps; Linux parent-death signal."""
import ctypes
import os
import signal
import sys

MEMORY_BYTES = 1536 * 1024 * 1024

class WindowsJob:
    def __init__(self, process):
        from ctypes import wintypes as w
        class Basic(ctypes.Structure):
            _fields_ = [('process_time', ctypes.c_int64), ('job_time', ctypes.c_int64), ('flags', w.DWORD),
                ('min_working', ctypes.c_size_t), ('max_working', ctypes.c_size_t), ('active', w.DWORD),
                ('affinity', ctypes.c_size_t), ('priority', w.DWORD), ('scheduling', w.DWORD)]
        class IO(ctypes.Structure):
            _fields_ = [(name, ctypes.c_uint64) for name in ['read_ops','write_ops','other_ops','read_bytes','write_bytes','other_bytes']]
        class Extended(ctypes.Structure):
            _fields_ = [('basic', Basic), ('io', IO), ('process_memory', ctypes.c_size_t),
                ('job_memory', ctypes.c_size_t), ('peak_process', ctypes.c_size_t), ('peak_job', ctypes.c_size_t)]
        self.api = ctypes.WinDLL('kernel32', use_last_error=True)
        signatures = {'CreateJobObjectW': ([w.LPVOID,w.LPCWSTR],w.HANDLE),
            'SetInformationJobObject': ([w.HANDLE,ctypes.c_int,w.LPVOID,w.DWORD],w.BOOL),
            'QueryInformationJobObject': ([w.HANDLE,ctypes.c_int,w.LPVOID,w.DWORD,w.LPVOID],w.BOOL),
            'AssignProcessToJobObject': ([w.HANDLE,w.HANDLE],w.BOOL),
            'TerminateJobObject': ([w.HANDLE,w.UINT],w.BOOL), 'CloseHandle': ([w.HANDLE],w.BOOL)}
        for name,(args,result) in signatures.items():
            function=getattr(self.api,name);function.argtypes=args;function.restype=result
        self.handle = self.api.CreateJobObjectW(None,None)
        if not self.handle: raise ctypes.WinError(ctypes.get_last_error())
        try:
            limits=Extended();limits.basic.flags=0x2000|0x100|0x200|0x8
            limits.basic.active=8;limits.process_memory=MEMORY_BYTES;limits.job_memory=MEMORY_BYTES
            if not self.api.SetInformationJobObject(self.handle,9,ctypes.byref(limits),ctypes.sizeof(limits)): raise ctypes.WinError(ctypes.get_last_error())
            if not self.api.AssignProcessToJobObject(self.handle,int(process._handle)): raise ctypes.WinError(ctypes.get_last_error())
            actual=Extended()
            if not self.api.QueryInformationJobObject(self.handle,9,ctypes.byref(actual),ctypes.sizeof(actual),None): raise ctypes.WinError(ctypes.get_last_error())
            self.profile={'kind':'windows-job','memory_bytes':int(actual.job_memory),'active_processes':int(actual.basic.active),
                'kill_on_parent_exit':bool(actual.basic.flags & 0x2000)}
        except Exception: self.close();raise
    def kill(self):
        if self.handle: self.api.TerminateJobObject(self.handle,1)
    def close(self):
        if self.handle: self.api.CloseHandle(self.handle);self.handle=None

def bind_worker(process):
    job=WindowsJob(process) if os.name=='nt' else None
    process._ocr_job=job
    return job.profile if job else {'kind':'posix-process-group','container_memory_limit_required':True,
        'kill_on_parent_exit':sys.platform=='linux'}

def kill_worker(process):
    if getattr(process,'_ocr_disposed',False): return
    job=getattr(process,'_ocr_job',None)
    if job: job.kill()
    elif os.name=='posix':
        if sys.platform=='linux':
            # Keep the child unreaped until group termination, so its PID cannot be reused.
            try: os.waitid(os.P_PID,process.pid,os.WEXITED|os.WNOHANG|os.WNOWAIT)
            except ChildProcessError: return
        elif process.poll() is not None: return
        try: os.killpg(process.pid,signal.SIGKILL)
        except ProcessLookupError: pass
    elif process.poll() is None: process.kill()

def worker_alive(process):
    if getattr(process,'_ocr_disposed',False): return False
    if sys.platform=='linux':
        try: return os.waitid(os.P_PID,process.pid,os.WEXITED|os.WNOHANG|os.WNOWAIT) is None
        except ChildProcessError: return False
    return process.poll() is None

def guard_parent():
    if sys.platform=='linux':
        expected=int(os.environ['OCR_SUPERVISOR_PID'])
        if os.getppid()!=expected: os._exit(1)
        libc=ctypes.CDLL(None,use_errno=True)
        if libc.prctl(1,int(signal.SIGKILL),0,0,0)!=0: raise OSError('Cannot bind worker lifetime')
        if os.getppid()!=expected: os._exit(1)
