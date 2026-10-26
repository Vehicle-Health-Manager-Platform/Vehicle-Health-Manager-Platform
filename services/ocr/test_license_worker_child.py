"""Explicit synthetic engine for child-process protocol testing only."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent/'src'))
from worker import child_loop
class Engine:
    def recognize(self,image):
        return [{'text':value,'confidence':.95} for value in ['号牌号码 粤B12345','VIN LTEST223456789012','所有人 合成私有姓名','住址 合成私有地址','123456 km']]
if __name__=='__main__':child_loop(Engine)
