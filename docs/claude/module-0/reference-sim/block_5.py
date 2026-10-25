import csv,sys,runpy,io,contextlib
f=io.StringIO()
with contextlib.redirect_stdout(f): g=runpy.run_path(sys.argv[3] if len(sys.argv)>3 else __file__.replace("block_5.py","blocks_2_3.py"),run_name='sim')
upd,D,cl,r=g['upd'],g['D'],g['cl'],g['r']; cat=g['cat']; ct=g['ct']
reg={b['inn'] for b in r('buyers.csv')}
stop={'0225011072':'BANKRUPTCY','5507213870':'EXCLUSION'}
bs={b['inn']:b for b in r('stub_buyer_scores.csv')}
us={u['number']:u for u in r('stub_upd_scores.csv')}
hist={u['buyer_inn'] for u in upd if u['out']==0}
lvl=lambda pd:'A' if pd<0.01 else 'B' if pd<0.03 else 'C' if pd<0.08 else 'D'
adv={'A':.9,'B':.85,'C':.75}; lgd={'A':.4,'B':.45,'C':.5}; lim={'A':30e6,'B':20e6,'C':10e6}
res={}; cand=[]
for u in upd:
  n=u['number']
  if u['out']==0: continue
  c=cat(u); b=u['buyer_inn']
  def no(x): res[n]=x
  if c>4: no('UPD_OVERDUE'); continue
  if b in stop: no('STOP_FACTOR'); continue
  if b not in reg: no('NO_BUYER_DATA'); continue
  if b not in hist: no('NO_PAYMENT_HISTORY'); continue
  pd=float(bs[b]['pd']); L=lvl(pd)
  s=us.get(n,{'p_delay':'0.1','p_overdue_90':'0.01','expected_delay_days':'3'})
  pdl,p90,ed=float(s['p_delay']),float(s['p_overdue_90']),int(s['expected_delay_days'])
  if L=='D': no('RISK_LEVEL_D'); continue
  if p90>0.10: no('OVERDUE_90_PROBABLE'); continue
  delay=pdl>0.30
  if delay and L=='C': no('DELAY_PROBABLE_LEVEL_C'); continue
  a={'A':adv['B'],'B':adv['C']}[L] if delay else adv[L]
  days=(u['due']-D).days
  if u['out']<300000: no('SMALL_AMOUNT'); continue
  if days<10: no('SHORT_TERM'); continue
  ead=u['out']*a; T=days+ed; f=lambda rate:ead*rate*T/365
  el=pd*T/365*lgd[L]*ead; fund=f(.16); op=f(.015); mar=f(.03); cap=f(.24)
  price=min(fund+el+op+mar,cap); inc=price-fund-el-op
  if inc<3000: no(f'INCOME_BELOW_MIN ({inc:.0f})'); continue
  liq=float(cl[u['client_inn']]['abs_liquidity']); dfr=int(ct[(u['client_inn'],u['contract_number'])]['deferral_days'])
  tr=0.07*(1.5 if liq<0.2 else 1)*(1.5 if dfr>=60 else 1)
  cand.append(dict(n=n,c=u['client_inn'],b=b,L=L,ead=ead,T=T,inc=inc,price=price,tr=tr,rank=tr*inc,date=u['upd_date'],delay=delay))
top=[]
for c in {x['c'] for x in cand}:
  xs=sorted([x for x in cand if x['c']==c],key=lambda x:(-x['rank'],x['date'],x['n']))
  top+=xs[:3]; [res.__setitem__(x['n'],'NOT_IN_TOP_N') for x in xs[3:]]
left={}
for x in sorted(top,key=lambda x:(-x['rank'],x['date'],x['n'])):
  rem=left.setdefault(x['b'],lim[x['L']])
  if rem<=0: res[x['n']]='LIMIT_EXHAUSTED'; continue
  e=min(x['ead'],rem); k=e/x['ead']; left[x['b']]=rem-e
  if x['inc']*k<3000: res[x['n']]='INCOME_BELOW_MIN'; continue
  res[x['n']]=f"OFFER {x['L']} ead={e:,.0f}{' (reduced from %s)'%f'{x[chr(101)+chr(97)+chr(100)]:,.0f}' if k<1 else ''} T={x['T']} price={x['price']*k:,.0f} rate={x['price']/x['ead']*365/x['T']:.2%} income={x['inc']*k:,.0f} TR={x['tr']:.4f} rank={x['rank']:,.0f}{' DELAY' if x['delay'] else ''}"
for n in sorted(res): print(n,res[n])
