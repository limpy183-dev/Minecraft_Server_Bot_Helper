"""Static route evidence: colour is elevation, not an assertion of shortest-path optimality."""
import json, pathlib, sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
import numpy as np

root=pathlib.Path(sys.argv[1])
names=['west-selected','north-selected','east-selected','south-selected','lower-floor-overhang','full-redstone']
survey=json.loads((root/'survey/before.json').read_text())
points=np.array([b['pos'] for b in survey['redstone']])
fig, axes=plt.subplots(2,3,figsize=(15,10),layout='constrained')
for ax,name in zip(axes.flat,names):
    ax.scatter(points[:,0]+.5,points[:,2]+.5,s=3,c='#d8dfe7',rasterized=True)
    file=root/name/'ticks.jsonl'
    if file.exists():
        track=[]
        for line in file.open():
            try: track.append(json.loads(line)['position'])
            except ValueError: break
        if len(track)>1:
            p=np.array(track); xy=p[:,[0,2]]
            segments=np.stack([xy[:-1],xy[1:]],axis=1)
            lines=LineCollection(segments,cmap='viridis',norm=plt.Normalize(55,80),linewidth=1.6)
            lines.set_array(p[:-1,1]);ax.add_collection(lines)
            ax.scatter(*xy[0],s=65,c='#111827',marker='o',label='Start',zorder=5)
            ax.scatter(*xy[-1],s=90,c='#e5484d',marker='X',label='End',zorder=6)
    ax.scatter([1118.5,1122.5],[129.5,117.5],s=90,facecolors='none',edgecolors='#e5484d',linewidth=1.4)
    ax.set(title=name,xlim=(1108,1185),ylim=(159,84),xlabel='Nether X',ylabel='Nether Z',aspect='equal')
    ax.spines[['top','right']].set_visible(False);ax.grid(alpha=.15)
fig.colorbar(plt.cm.ScalarMappable(norm=plt.Normalize(55,80),cmap='viridis'),ax=axes,shrink=.8,label='Player elevation (Y)')
fig.suptitle('Basalt farm: actual player trajectories\nGrey = original redstone; red rings = two persistent selected-block failures',fontsize=16)
fig.savefig(root/'routes.png',dpi=160);plt.close(fig)

results=json.loads((root/'summary.json').read_text())
fig,ax=plt.subplots(figsize=(12,max(4,len(results)*.5)),layout='constrained')
groups={'Mining':['MINING','CLEARING'],'Travel':['WALKING','BRIDGING','PLANNING'],'Collect':['COLLECTING'],
        'Protection':['COVERING','PREPARING'],'Scan / wait':['SCANNING','WAITING','DONE','OFF'],
        'Other':['TIDYING','FIGHTING','SURFACING','SELLING']}
left=np.zeros(len(results))
for label,phases in groups.items():
    value=np.array([sum(r['phases'].get(p,0) for p in phases)/20 for r in results])
    ax.barh([r['case'] for r in results],value,left=left,label=label);left+=value
ax.set(xlabel='Observed seconds at nominal 20 ticks per second',title='Where the bot spent its time (controller phases)')
ax.invert_yaxis();ax.spines[['top','right']].set_visible(False);ax.legend(ncols=3,loc='lower right')
fig.savefig(root/'phase-time.png',dpi=160)
plt.close(fig)

full=[json.loads(line) for line in (root/'full-redstone/ticks.jsonl').open()]
tail=[row for row in full if row['tick']>=14660]
t=np.array([row['tick']/20 for row in tail])
fig,axes=plt.subplots(2,1,figsize=(12,6),sharex=True,layout='constrained')
axes[0].plot(t,[row['health'] for row in tail],color='#b91c1c',linewidth=2)
axes[0].axhline(12,color='#64748b',linestyle='--',label='Low-health threshold')
axes[0].set(ylabel='Health (HP)',ylim=(10,21),title='Full run: low-floor pickup, upward return and lava damage')
axes[0].legend(loc='lower left')
axes[1].plot(t,[row['position'][1] for row in tail],color='#0369a1',linewidth=2)
axes[1].set(ylabel='Player elevation (Y)',xlabel='Game time (seconds at nominal 20 ticks/s)')
for ax in axes:
    ax.fill_between(t,0,1,where=[row['lava'] for row in tail],color='#f97316',alpha=.25,transform=ax.get_xaxis_transform(),label='Player flagged in lava')
    ax.grid(alpha=.2);ax.spines[['top','right']].set_visible(False)
axes[1].legend(loc='lower right')
fig.savefig(root/'damage-trace.png',dpi=160);plt.close(fig)
(root/'full-redstone/damage-window.json').write_text(json.dumps(tail,indent=2))
