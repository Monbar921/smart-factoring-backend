import csv,re,sys,datetime as dt
D=dt.date.fromisoformat(sys.argv[2] if len(sys.argv)>2 else '2026-10-24')
d=sys.argv[1]
r=lambda f:list(csv.DictReader(open(f'{d}/{f}',encoding='utf-8-sig'),delimiter=';'))
cl={c['inn']:c for c in r('clients.csv') if c['edo_consent']=='true' and c['factoring_client']=='false'}
ct={(c['client_inn'],c['number']):c for c in r('contracts.csv')}
upd=[u for u in r('upds.csv') if u['client_inn'] in cl and dt.date.fromisoformat(u['upd_date'])<=D]
for u in upd:
  u['amount']=float(u['amount']); u['out']=u['amount']; u['due']=dt.date.fromisoformat(u['upd_date'])+dt.timedelta(int(ct[(u['client_inn'],u['contract_number'])]['deferral_days'])); u['paid']=None
pays=sorted([p for p in r('payments.csv') if p['client_inn'] in cl and dt.date.fromisoformat(p['payment_date'])<=D],key=lambda p:(p['payment_date'],p['source_id']))
def word(tok,txt): return re.search(r'(?<![\w-])'+re.escape(tok)+r'(?![\w-])',txt or '') is not None
key=lambda u:(u['upd_date'],u['number'])
for p in pays:
  amt=float(p['amount']); pd_=dt.date.fromisoformat(p['payment_date'])
  pair=[u for u in upd if u['client_inn']==p['client_inn'] and u['buyer_inn']==p['payer_inn']]
  opn=lambda us:sorted([u for u in us if u['out']>0],key=key)
  byn=[u for u in pair if word(u['number'],p['purpose'])]
  cnums={u['contract_number'] for u in pair}
  byc=[c for c in cnums if word(c,p['purpose'])]
  log=[]
  def alloc(u,rule):
    global amt
    x=min(amt,u['out']); u['out']-=x; amt-=x; log.append((u['number'],x,rule))
    if u['out']==0: u['paid']=pd_
  if byn:
    u=byn[0]
    if u['out']>0: alloc(u,'UPD')
    for v in opn([w for w in pair if w['contract_number']==u['contract_number']]):
      if amt<=0: break
      alloc(v,'CARRY_CONTRACT')
  elif byc:
    for v in opn([w for w in pair if w['contract_number']==byc[0]]):
      if amt<=0: break
      alloc(v,'CONTRACT_FIFO')
  for v in opn(pair):
    if amt<=0: break
    alloc(v,'BUYER_FIFO' if not byn and not byc else 'CARRY_BUYER')
  print(p['source_id'],log,'unallocated' if amt>0 else '',amt if amt>0 else '')
def cat(u):
  dd=(u['due']-D).days; part=u['out']<u['amount']
  if dd>=0: return (3 if part else 1) if dd<=30 else (4 if part else 2)
  return (6 if part else 8) if -dd<=30 else (5 if part else 7)
for u in upd:
  if u['out']>0: print(u['number'],u['client_inn'][-1],u['buyer_inn'],'out',u['out'],'due',u['due'],'days',(u['due']-D).days,'cat',cat(u),u['control_case'])
  else: print(u['number'],'PAID',u['paid'],'delay',(u['paid']-u['due']).days)
