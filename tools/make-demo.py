"""Generate the two original offline listening samples with Python and FFmpeg."""
import array, math, sys, subprocess, tempfile, wave
from pathlib import Path
F = sys.argv[1] if len(sys.argv) > 1 else 'ffmpeg'
out = Path(__file__).resolve().parents[1]/'app/src/main/assets/demo'
out.mkdir(parents=True, exist_ok=True)
rate, tempo, length = 44100, 120, 36
beat = 60 / tempo
tau = math.tau
songs = [
    ("blue-hour", [[45,52,55,60],[41,48,52,57],[48,55,59,64],[43,50,53,59]]),
    ("afterimage", [[47,54,57,62],[43,50,54,59],[45,52,55,60],[42,49,52,57]]),
]
def freq(n): return 440 * (2 ** ((n-69)/12))
for mode, (name, groups) in enumerate(songs):
    chords = [[freq(n) for n in notes] for notes in groups]
    data = array.array('h')
    for n in range(length*rate):
        time = n / rate
        section = int(time/(beat*8)) % len(chords)
        s = chords[section]
        t = time % beat
        eighth = int(time / (beat/2))
        note = s[[0,2,1,3,2,1,3,1][(eighth+mode)%8]] * 2
        et = time % (beat/2)
        chord_t = time % (8*beat)
        pad_gain = min(1, chord_t*7, (8*beat-chord_t)*7)
        pad = sum(math.sin(tau * f * time + i*.3) + .16*math.sin(tau * f * 2.005 * time) for i,f in enumerate(s)) * .027 * pad_gain
        arp = (math.sin(tau*note*et) + .3*math.sin(tau*note*et*2.002))*.195*math.exp(-et*13)*min(1,et*290)
        kick = math.sin(tau*(51*t+2.7*(1-math.exp(-t*48))))*.26*math.exp(-t*16)*min(1,t*350)
        bass = math.sin(tau*s[0]/2*t)*.13*math.exp(-t*3)*min(1,t*185)
        hat = math.sin(tau*7180*et+2.1*math.sin(tau*3322*et))*.032*math.exp(-et*66)*min(1,et*500)
        amp = min(1, time*2, (length-time)*.66)
        l = amp*(pad+kick+bass+hat+arp*(.78+.18*math.sin(time*2.3)))
        r = amp*(pad+kick+bass-hat*.53+arp*(.78-.18*math.sin(time*2.3)))
        data.extend((int(l*30000),int(r*30000)))
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)/'demo.wav'
        with wave.open(str(path),'wb') as wav:
            wav.setparams((2,2,rate,0,'NONE','not compressed'))
            wav.writeframes(data.tobytes())
        subprocess.run([F,'-v','error','-y','-i',str(path),'-c:a','aac','-b:a','112k','-metadata','album=Overtone Sessions',str(out/(name+'.m4a'))],check=True)
