"""Summarise server-confirmed edits and client-tick routes; does not infer a pass from missing blocks."""
import collections, csv, json, math, pathlib, sys


def distance(a, b): return math.dist(a, b)
def position(p): return (p['x'],p['y'],p['z']) if isinstance(p, dict) else tuple(p)


def analyse(folder):
    if not (folder/'end.json').exists(): return None
    end = json.loads((folder/'end.json').read_text())
    before = json.loads((folder/'before.json').read_text())
    after = json.loads((folder/'after.json').read_text())
    events = json.loads((folder/'events.json').read_text())
    counts = collections.Counter(e['kind'] for e in events)
    breaks = collections.Counter(e['block'] for e in events if e['kind']=='break')
    places = collections.Counter(e['block'] for e in events if e['kind']=='place')
    pickups, consumed = collections.Counter(), collections.Counter()
    previous = None; travel = damage = 0; phases = collections.Counter(); minhealth = 20
    last_cell = None; cells = []; cell_visits=[]; routes = []; route = None; active_key = None; frames=0; server_ticks=set()
    for line in (folder/'ticks.jsonl').open():
        r = json.loads(line); frames += 1
        server_ticks.add(r['server_tick'])
        phases[r['phase']] += 1; minhealth = min(minhealth,r['health'])
        cell = tuple(math.floor(n) for n in r['position'])
        if cell != last_cell: cells.append(cell); cell_visits.append((cell,r['tick'],r.get('target'))); last_cell = cell
        if previous:
            travel += distance(r['position'],previous['position'])
            damage += max(0,previous['health']-r['health'])
            for item in set(r['inventory']) | set(previous['inventory']):
                change = r['inventory'].get(item,0)-previous['inventory'].get(item,0)
                if change > 0: pickups[item]+=change
                elif change < 0: consumed[item]-=change
        key=tuple(map(tuple,r['path_nodes'])) if r['path_nodes'] and r['route_state']!='none' else None
        if key != active_key:
            if route:
                route['end_tick']=r['tick']; route['seconds']=(r['tick']-route['start_tick'])/20
                route['end_position']=r['position']; route['endpoint_distance']=distance(route['start_position'],r['position'])
                route['travel_to_endpoint_ratio']=route['travel']/route['endpoint_distance'] if route['endpoint_distance']>1 else None
                routes.append(route)
            route = None
            if key:
                route=dict(start_tick=r['tick'],target=r.get('target'),start_position=r['position'],nodes=r['path_nodes'],
                    node_count=r['path_length'],nodes_considered=r['nodes_considered'],predicted_ticks=r['path_cost_ticks'],
                    path_geometry=sum(distance(a,b) for a,b in zip(key,key[1:])),travel=0)
            active_key=key
        if route and previous: route['travel']+=distance(r['position'],previous['position'])
        previous=r
    if route:
        route.update(end_tick=previous['tick'],seconds=(previous['tick']-route['start_tick'])/20,end_position=previous['position'],unfinished=True)
        routes.append(route)
    decisions=[]
    for e in events:
        if e['kind']!='selection': continue
        candidates=e['candidates']; chosen=next((c for c in candidates if position(c['pos'])==tuple(e['pos'])),None)
        if not chosen: continue
        # Production defaults: reachable-first, storage-last disabled, 0.35-block near-tie slack.
        eligible_class=[c for c in candidates if c['reachable']==candidates[0]['reachable']]
        nearest=min(c['distance'] for c in eligible_class)
        decisions.append(dict(tick=e['observed_tick'],pos=e['pos'],distance=chosen['distance'],
            nearest_same_priority=nearest,extra_distance=chosen['distance']-nearest,
            global_nearest=min(c['distance'] for c in candidates),reachable=chosen['reachable'],
            violated=chosen['reachable']!=candidates[0]['reachable'] or chosen['distance']>nearest+0.350001,
            global_audit=e.get('global_original_position_audit')))
    oscillations=[]
    for i in range(len(cell_visits)-4):
        five=cell_visits[i:i+5]
        if five[0][0]==five[2][0]==five[4][0] and five[1][0]==five[3][0] and five[-1][1]-five[0][1]<=120 and five[0][2] is not None and all(v[2]==five[0][2] for v in five):
            oscillations.append(dict(start_tick=five[0][1],end_tick=five[-1][1],cells=[five[0][0],five[1][0]],target=five[0][2]))
    global_misses=[d for d in decisions if (a:=d['global_audit']) and a.get('distance') is not None and ((a['reachable'] and not d['reachable']) or a['reachable']==d['reachable'] and d['distance']>a['distance']+.350001)]
    result=dict(case=folder.name,frames=frames,seconds=len(server_ticks)/20,travel_blocks=travel,damage=damage,min_health=minhealth,
        end=end,phases=phases,events=counts,breaks=breaks,placements=places,pickups=pickups,inventory_decreases=consumed,
        original_redstone=len(before['redstone']),remaining_redstone=len(after['redstone']),
        remaining_selected=[b for b in after['redstone'] if b['block']=='minecraft:redstone_block'],
        retries=[e for e in events if e['kind']=='retry'],stuck=[e for e in events if e['kind']=='stuck'],
        immediate_cell_reversals=sum(a==c for a,b,c in zip(cells,cells[1:],cells[2:])),
        repeated_cell_entries=sum(n-1 for n in collections.Counter(cells).values() if n>1),
        route_count=len(routes),decisions=len(decisions),priority_violations=sum(d['violated'] for d in decisions),
        max_same_priority_extra=max((d['extra_distance'] for d in decisions),default=None),
        global_audited_decisions=sum(d['global_audit'] is not None for d in decisions),global_priority_misses=global_misses,oscillation_sequences=oscillations)
    (folder/'metrics.json').write_text(json.dumps(result,indent=2))
    (folder/'routes.json').write_text(json.dumps(routes,indent=2))
    (folder/'decisions.json').write_text(json.dumps(decisions,indent=2))
    fields=['start_tick','end_tick','seconds','target','node_count','nodes_considered','predicted_ticks','path_geometry','travel','endpoint_distance','travel_to_endpoint_ratio','unfinished']
    with (folder/'routes.csv').open('w',newline='') as f:
        w=csv.DictWriter(f,fields,extrasaction='ignore'); w.writeheader(); w.writerows(routes)
    return result


if __name__=='__main__':
    assert distance((0,0,0),(3,4,0))==5
    root=pathlib.Path(sys.argv[1]); results=[]
    for folder in root.iterdir():
        if folder.is_dir():
            result=analyse(folder)
            if result: results.append(result)
    (root/'summary.json').write_text(json.dumps(results,indent=2))
    for r in results:
        print(r['case'], 'seconds',round(r['seconds'],1),'breaks',sum(r['breaks'].values()),'places',sum(r['placements'].values()),
            'damage',r['damage'],'remaining',r['remaining_redstone'],'retries',len(r['retries']),'stuck',len(r['stuck']),
            'priority violations',r['priority_violations'],'state',r['end']['state'])
