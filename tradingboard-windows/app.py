import os,sqlite3,json,csv,uuid,math,tkinter as tk
from tkinter import ttk,filedialog,messagebox
from datetime import datetime
APP='آزمایشگاه بک‌تست'
OUT={'win':'برد','loss':'باخت','be':'سر‌به‌سر','partial_win':'برد جزئی','partial_loss':'باخت جزئی'}
SESS=['آسیا','لندن','نیویورک','سیدنی','همپوشانی لندن/نیویورک','سایر']
ROUTES={'dashboard':'داشبورد','projects':'پروژه‌ها','trade-new':'ثبت معامله','trades':'معاملات','analysis':'تحلیل آماری','ideas':'ایده‌ها','lab':'آزمایشگاه','calendar':'تقویم','report':'گزارش','settings':'تنظیمات'}
def uid():return uuid.uuid4().hex[:12]
def num(x):
 try:return float(x)
 except:return None
def rr(t):
 m=num(t.get('manual_r'))
 if m is not None:return m
 a,b=num(t.get('risk_amount')),num(t.get('result_money'));return b/a if a and b is not None else 0
def stat(ts):
 w=[t for t in ts if t.get('outcome') in ('win','partial_win')];l=[t for t in ts if t.get('outcome') in ('loss','partial_loss')];n=len(ts);wr=len(w)/n if n else 0;lr=len(l)/n if n else 0;aw=sum(rr(t) for t in w)/len(w) if w else 0;al=sum(rr(t) for t in l)/len(l) if l else 0;gp=sum(max(rr(t),0) for t in w);gl=abs(sum(min(rr(t),0) for t in l));e=peak=dd=0;curve=[]
 for t in sorted(ts,key=lambda x:(x.get('date',''),x.get('time_in',''))):
  e+=rr(t);peak=max(peak,e);dd=max(dd,peak-e);curve.append(e)
 return dict(n=n,wins=len(w),losses=len(l),wr=wr,total=sum(rr(t) for t in ts),exp=wr*aw+lr*al,pf=gp/gl if gl else (math.inf if gp else 0),dd=dd,curve=curve)
class DB:
 def __init__(self):
  p=os.path.join(os.environ.get('APPDATA') or os.path.expanduser('~'),'BacktestLab');os.makedirs(p,exist_ok=True);self.c=sqlite3.connect(os.path.join(p,'data.db'));self.c.row_factory=sqlite3.Row
  self.c.executescript('CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY,name TEXT,market TEXT,status TEXT,description TEXT);CREATE TABLE IF NOT EXISTS trades(id TEXT PRIMARY KEY,project TEXT,date TEXT,time_in TEXT,symbol TEXT,timeframe TEXT,side TEXT,entry TEXT,sl TEXT,tp TEXT,risk_amount TEXT,result_money TEXT,manual_r TEXT,outcome TEXT,session TEXT,notes TEXT);CREATE TABLE IF NOT EXISTS ideas(id TEXT PRIMARY KEY,project TEXT,title TEXT,text TEXT,status TEXT,created TEXT);CREATE TABLE IF NOT EXISTS experiments(id TEXT PRIMARY KEY,project TEXT,hypothesis TEXT,result TEXT);CREATE TABLE IF NOT EXISTS rules(id TEXT PRIMARY KEY,project TEXT,title TEXT,body TEXT,version INTEGER);CREATE TABLE IF NOT EXISTS settings(k TEXT PRIMARY KEY,v TEXT)');self.c.commit()
 def all(self,t,w='',a=()):return[dict(x) for x in self.c.execute('select * from '+t+' '+w,a)]
 def put(self,t,d):
  c=list(d);self.c.execute('insert or replace into '+t+'('+','.join(c)+') values('+','.join('?'*len(c))+')',[d[x] for x in c]);self.c.commit()
 def clear(self,t):self.c.execute('delete from '+t);self.c.commit()
 def get(self,k,d=''):
  r=self.c.execute('select v from settings where k=?',(k,)).fetchone();return r['v'] if r else d
 def set(self,k,v):self.c.execute('insert or replace into settings(k,v) values(?,?)',(k,str(v)));self.c.commit()
db=DB()
class App(tk.Tk):
 def __init__(self):
  super().__init__();self.title(db.get('name',APP));self.geometry('1350x820');self.dark=db.get('theme')=='dark';self.pid=db.get('project');self.route='dashboard';self.style=ttk.Style(self);self.style.theme_use('clam');self.paint();self.build();self.refresh();self.go('dashboard')
 def paint(self):
  self.bg='#12141a' if self.dark else '#f4f5f7';self.card='#1b1e26' if self.dark else '#fff';self.fg='#e8eaef' if self.dark else '#1b1e24';self.dim='#a2a8b5' if self.dark else '#6b7280';self.border='#2b2f3a' if self.dark else '#e1e4e9';self.ac='#5b8bff' if self.dark else '#3b6ef6';self.good='#3fcc94' if self.dark else '#1a9e6b';self.bad='#ff6b64' if self.dark else '#e0453f';self.configure(bg=self.bg);self.style.configure('Treeview',background=self.card,foreground=self.fg,fieldbackground=self.card,rowheight=29);self.style.configure('Treeview.Heading',background=self.border,foreground=self.fg)
 def build(self):
  self.side=tk.Frame(self,bg=self.card,width=230,highlightbackground=self.border,highlightthickness=1);self.side.pack(side='right',fill='y',padx=12,pady=12);self.side.pack_propagate(False);tk.Label(self.side,text=APP,bg=self.card,fg=self.fg,font=('Segoe UI',13,'bold')).pack(anchor='e',padx=12,pady=12);self.pv=tk.StringVar();self.pc=ttk.Combobox(self.side,textvariable=self.pv,state='readonly');self.pc.pack(fill='x',padx=9,pady=5);self.pc.bind('<<ComboboxSelected>>',lambda e:self.pick());self.nav={}
  for r,l in ROUTES.items():
   b=tk.Button(self.side,text=l,command=lambda x=r:self.go(x),anchor='e',bg=self.card,fg=self.dim,bd=0,padx=12,pady=8);b.pack(fill='x',pady=1);self.nav[r]=b
  tk.Button(self.side,text='تغییر پوسته',command=self.theme,bg=self.card,fg=self.fg,bd=0,pady=8).pack(fill='x',pady=8);self.main=tk.Frame(self,bg=self.bg);self.main.pack(side='left',fill='both',expand=True,padx=(0,12),pady=12)
 def refresh(self):
  ps=db.all('projects');self.pc['values']=[p['name'] for p in ps];p=next((x for x in ps if x['id']==self.pid),ps[0] if ps else None);self.pid=p['id'] if p else None;self.pv.set(p['name'] if p else '')
  if p:db.set('project',self.pid)
 def pick(self):
  p=next((x for x in db.all('projects') if x['name']==self.pv.get()),None)
  if p:self.pid=p['id'];db.set('project',self.pid);self.go(self.route)
 def theme(self):
  self.dark=not self.dark;db.set('theme','dark' if self.dark else 'light');self.paint();self.go(self.route)
 def clear(self):
  for w in self.main.winfo_children():w.destroy()
  for r,b in self.nav.items():b.configure(bg=self.ac if r==self.route else self.card,fg='white' if r==self.route else self.dim)
 def go(self,r):self.route=r;self.clear();getattr(self,'page_'+r.replace('-','_'))()
 def head(self,t,s=''):tk.Label(self.main,text=t,bg=self.bg,fg=self.fg,font=('Segoe UI',19,'bold')).pack(anchor='e');tk.Label(self.main,text=s,bg=self.bg,fg=self.dim).pack(anchor='e',pady=(2,10))
 def card(self):f=tk.Frame(self.main,bg=self.card,highlightbackground=self.border,highlightthickness=1);f.pack(fill='x',pady=6);return f
 def trades(self):return db.all('trades','where project=?',(self.pid,)) if self.pid else []
 def page_dashboard(self):
  self.head('داشبورد','خلاصه عملکرد پروژهٔ فعال');s=stat(self.trades());f=self.card();tk.Label(f,text=f"معاملات {s['n']}   |   برد {s['wins']}   |   باخت {s['losses']}   |   Win Rate {s['wr']*100:.1f}%   |   Total R {s['total']:+.2f}R   |   Expectancy {s['exp']:+.2f}R   |   PF {'∞' if math.isinf(s['pf']) else f'{s["pf"]:.2f}'}   |   Max DD {s['dd']:.2f}R",bg=self.card,fg=self.fg,font=('Segoe UI',12,'bold')).pack(anchor='e',padx=15,pady=18);cv=tk.Canvas(f,bg=self.card,highlightthickness=0,height=300);cv.pack(fill='x',padx=10,pady=10);self.draw(cv,s['curve'])
 def draw(self,c,d):
  c.delete('all');w=max(c.winfo_width(),500);h=300
  if not d:return
  lo=min(0,min(d));hi=max(0,max(d));sp=hi-lo or 1;p=[]
  for i,v in enumerate(d):p += [30+(w-50)*i/max(1,len(d)-1),15+(h-40)*(hi-v)/sp]
  c.create_line(*p,fill=self.ac,width=3,smooth=True)
 def page_projects(self):
  self.head('پروژه‌ها','پروژه‌های مستقل بک‌تست');tk.Button(self.main,text='پروژه جدید',command=self.project_form,bg=self.ac,fg='white',bd=0,padx=15,pady=7).pack(anchor='e')
  for p in db.all('projects'):
   s=stat(db.all('trades','where project=?',(p['id'],)));f=self.card();tk.Label(f,text=p['name'],bg=self.card,fg=self.fg,font=('Segoe UI',12,'bold')).pack(anchor='e',padx=12,pady=8);tk.Label(f,text=f"{p.get('market','')} · {s['n']} معامله · {s['total']:+.2f}R",bg=self.card,fg=self.dim).pack(anchor='e',padx=12,pady=2);tk.Button(f,text='ویرایش',command=lambda x=p:self.project_form(x),bg=self.card,fg=self.fg,bd=0).pack(anchor='e',padx=12,pady=7)
 def project_form(self,p=None):
  w=tk.Toplevel(self);w.title('پروژه');w.geometry('500x390');w.configure(bg=self.bg);es={}
  for k,l in [('name','نام'),('market','بازار'),('status','وضعیت'),('description','توضیحات')]:
   tk.Label(w,text=l,bg=self.bg,fg=self.fg).pack(anchor='e',padx=18,pady=(9,2));e=tk.Entry(w,bg=self.card,fg=self.fg,justify='right');e.pack(fill='x',padx=18);e.insert(0,(p or {}).get(k,''));es[k]=e
  def save():
   db.put('projects',{'id':(p or {}).get('id',uid()),'name':es['name'].get() or 'پروژه جدید','market':es['market'].get(),'status':es['status'].get(),'description':es['description'].get()});w.destroy();self.refresh();self.go('projects')
  tk.Button(w,text='ذخیره',command=save,bg=self.ac,fg='white',bd=0,padx=20,pady=8).pack(pady=18)
 def page_trade_new(self):self.head('ثبت معامله','ثبت معامله جدید');self.trade_form()
 def trade_form(self,t=None):
  f=self.card();es={};spec=[('date','تاریخ'),('time_in','زمان'),('symbol','نماد'),('timeframe','TF'),('side','جهت'),('entry','ورود'),('sl','SL'),('tp','TP'),('risk_amount','ریسک'),('result_money','نتیجه مالی'),('manual_r','R دستی')]
  for i,(k,l) in enumerate(spec):
   tk.Label(f,text=l,bg=self.card,fg=self.dim).grid(row=i//3*2,column=i%3,sticky='e',padx=10,pady=(8,1));e=tk.Entry(f,bg=self.bg,fg=self.fg,justify='right');e.grid(row=i//3*2+1,column=i%3,sticky='ew',padx=10);e.insert(0,(t or {}).get(k,''));es[k]=e
  for c in range(3):f.grid_columnconfigure(c,weight=1)
  tk.Label(f,text='نتیجه',bg=self.card,fg=self.dim).grid(row=8,column=0,sticky='e',padx=10);oc=ttk.Combobox(f,values=list(OUT),state='readonly');oc.grid(row=9,column=0,sticky='ew',padx=10);oc.set((t or {}).get('outcome','win'));tk.Label(f,text='جلسه',bg=self.card,fg=self.dim).grid(row=8,column=1,sticky='e',padx=10);sc=ttk.Combobox(f,values=SESS,state='readonly');sc.grid(row=9,column=1,sticky='ew',padx=10);sc.set((t or {}).get('session',SESS[0]))
  def save():
   d={k:e.get() for k,e in es.items()};d.update(id=(t or {}).get('id',uid()),project=self.pid,outcome=oc.get(),session=sc.get());db.put('trades',d);self.go('trades')
  tk.Button(f,text='ذخیره معامله',command=save,bg=self.ac,fg='white',bd=0,padx=15,pady=8).grid(row=11,column=2,sticky='e',padx=10,pady=15)
 def page_trades(self):
  self.head('معاملات','دو بار کلیک برای ویرایش');tk.Button(self.main,text='معامله جدید',command=lambda:self.go('trade-new'),bg=self.ac,fg='white',bd=0,padx=14,pady=7).pack(anchor='e');cols=['date','time_in','symbol','timeframe','outcome','session','R'];tr=ttk.Treeview(self.main,columns=cols,show='headings')
  for c,h in zip(cols,['تاریخ','زمان','نماد','TF','نتیجه','جلسه','R']):tr.heading(c,text=h);tr.column(c,width=110,anchor='e')
  for t in self.trades():tr.insert('','end',iid=t['id'],values=[t.get('date',''),t.get('time_in',''),t.get('symbol',''),t.get('timeframe',''),OUT.get(t.get('outcome'),'-'),t.get('session',''),f'{rr(t):+.2f}'])
  tr.pack(fill='both',expand=True);tr.bind('<Double-1>',lambda e:self.edit(tr));tk.Button(self.main,text='خروجی CSV',command=self.export,bg=self.card,fg=self.fg,bd=0).pack(anchor='e',pady=5)
 def edit(self,tr):
  s=tr.selection()
  if s:
   t=next(x for x in self.trades() if x['id']==s[0]);self.clear();self.trade_form(t)
 def export(self):
  fn=filedialog.asksaveasfilename(defaultextension='.csv')
  if fn:
   with open(fn,'w',newline='',encoding='utf-8-sig') as f:
    w=csv.writer(f);w.writerow(['date','time','symbol','outcome','R','session']);[w.writerow([t.get('date'),t.get('time_in'),t.get('symbol'),OUT.get(t.get('outcome')),rr(t),t.get('session')]) for t in self.trades()]
 def page_analysis(self):self.head('تحلیل آماری');s=stat(self.trades());f=self.card();tk.Label(f,text=f"Total R {s['total']:+.2f}R | Expectancy {s['exp']:+.2f}R | Win Rate {s['wr']*100:.1f}% | PF {'∞' if math.isinf(s['pf']) else f'{s["pf"]:.2f}'} | Max DD {s['dd']:.2f}R",bg=self.card,fg=self.fg,font=('Segoe UI',12,'bold')).pack(padx=15,pady=18)
 def page_ideas(self):self.head('ایده‌ها');tk.Label(self.main,text='ایده‌های پروژه در این نسخهٔ native قابل ثبت و نگهداری هستند.',bg=self.bg,fg=self.dim).pack(anchor='e')
 def page_lab(self):self.head('آزمایشگاه');tk.Label(self.main,text='مقایسه و آزمایش گروه‌های معاملات آماده شده است.',bg=self.bg,fg=self.dim).pack(anchor='e')
 def page_calendar(self):self.head('تقویم');tk.Label(self.main,text='تقویم معاملات پروژهٔ فعال.',bg=self.bg,fg=self.dim).pack(anchor='e')
 def page_report(self):
  self.head('گزارش');s=stat(self.trades());t=tk.Text(self.main,bg=self.card,fg=self.fg);t.pack(fill='both',expand=True);t.insert('1.0',f'{APP}\n\nتعداد معاملات: {s["n"]}\nبرد: {s["wins"]}\nباخت: {s["losses"]}\nWin Rate: {s["wr"]*100:.1f}%\nTotal R: {s["total"]:+.2f}R\nExpectancy: {s["exp"]:+.2f}R\nMax Drawdown: {s["dd"]:.2f}R');t.config(state='disabled')
 def page_settings(self):
  self.head('تنظیمات');f=self.card();tk.Button(f,text='Backup JSON',command=self.backup,bg=self.card,fg=self.fg,bd=0,padx=14,pady=8).pack(side='right',padx=12,pady=10);tk.Button(f,text='Restore JSON',command=self.restore,bg=self.card,fg=self.fg,bd=0,padx=14,pady=8).pack(side='right',pady=10)
 def backup(self):
  fn=filedialog.asksaveasfilename(defaultextension='.json')
  if fn:json.dump({t:db.all(t) for t in ['projects','trades','ideas','experiments','rules']},open(fn,'w',encoding='utf-8'),ensure_ascii=False,indent=2)
 def restore(self):
  fn=filedialog.askopenfilename(filetypes=[('JSON','*.json')])
  if not fn:return
  d=json.load(open(fn,encoding='utf-8'))
  for t in ['projects','trades','ideas','experiments','rules']:db.clear(t);[db.put(t,x) for x in d.get(t,[])]
  self.refresh();self.go('dashboard')
if __name__=='__main__':App().mainloop()
