#!/usr/bin/env python3
"""Replay frozen PR19 faults with isolated Fake evidence; never read .env."""
import argparse
from datetime import datetime, timezone
import json
import re
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET
from run_pr18 import diagnostic, input_manifest, run_process, test_environment, validate_evidence

VARIANTS = {
 'F-01':['known-l1','known-l2','trusted-order-l3','no-order-l3'],
 'F-02':['same-event','same-charge-bt','concurrent-same-bt'],
 'F-03':['refund-bt-first','dispute-bt-first','old-failure-after-success','payout-return-first'],
 'F-04':['body-byte-tamper','wrong-signature','wrong-endpoint-secret','outside-tolerance','inbox-persistence-failure','legal-raw-bytes'],
 'F-05':['payment-before-send','payment-accepted-timeout','payment-expired-key','refund-before-send','refund-accepted-timeout','refund-expired-key'],
 'F-06':['transaction-before-commit','transaction-after-commit','remote-before-call','remote-after-call','lease-fencing-controlled','inbox-before-commit','inbox-after-commit','outbox-after-send','consumer-after-commit-before-ack'],
 'F-07':['broker-outage','outbox-after-send','consumer-commit-before-ack'],
 'F-08':['same-key-payment','double-refund','refund-share','refund-dispute','duplicate-bank-allocation','shared-bank-line','same-bt-multi-entry'],
}
CLASSES={**{f'F-0{i}':['Pr19WebhookFaultIT'] for i in range(1,5)},'F-05':['Pr19ProviderFaultIT'],'F-06':['Pr19ProcessFaultIT','Pr19KafkaFaultIT'],'F-07':['Pr19KafkaFaultIT'],'F-08':['Pr19MultiProcessIT']}
SUITE_CASE_COUNTS={'Pr19WebhookFaultIT':17,'Pr19ProviderFaultIT':6,'Pr19ProcessFaultIT':7,'Pr19KafkaFaultIT':5,'Pr19MultiProcessIT':7}
def qualified(name):return 'com.paymentsledger.'+('notification.' if name=='Pr19KafkaFaultIT' else 'payment.')+name
COUNTS=('requests','accepted','webhooks','statusTransitions','journals','merchantEffects','providerMoneyEffects')
def selected(fault):
 if fault!='all' and fault not in VARIANTS:raise ValueError('unknown fault ID')
 return list(VARIANTS) if fault=='all' else [fault]
def build_command(fault):
 selected(fault)
 command=['./mvnw','-B','clean','verify','-Dpr18.seeds=100','-Dpr18.maxSteps=100','-Dpr18.seed=180018']
 if fault!='all':command.append('-Dit.test='+','.join(CLASSES[fault]))
 return command

def validate_faults(folder,fault,expected_commit=None):
 actual=[]
 for ident in selected(fault):
  for variant in VARIANTS[ident]:
   try:record=json.loads((folder/f'{ident}-{variant}.json').read_text())
   except (OSError,ValueError) as failure:raise ValueError(f'missing or malformed {ident}/{variant}') from failure
   if not isinstance(record,dict) or any(record.get(k)!=v for k,v in {'id':ident,'variant':variant,'testId':ident+'/'+variant,'status':'PASS','providerEvidence':'FAKE_ONLY','bankEvidence':'SIMULATED_BANK_EVIDENCE'}.items()):raise ValueError(f'invalid identity/outcome {ident}/{variant}')
   for name in ('seed','elapsedMillis','recoveryMillis'):
    if type(record.get(name)) is not int or record[name]<0:raise ValueError(f'invalid {name} {ident}/{variant}')
   for name in ('injection','recovery','limitations','startedAtUtc','completedAtUtc','dataset','operationKeys','observations','intermediateEvidence','finalEvidence'):
    if not record.get(name):raise ValueError(f'missing {name} {ident}/{variant}')
   for name in ('dataset','observations','intermediateEvidence','finalEvidence'):
    if not isinstance(record.get(name),dict) or not record[name]:raise ValueError(f'nonstructured {name} {ident}/{variant}')
   if not isinstance(record.get('operationKeys'),(dict,list)) or not record['operationKeys']:raise ValueError('invalid operation keys')
   for name in ('title','expected','injection','recovery','limitations','startedAtUtc','completedAtUtc'):
    if not isinstance(record.get(name),str) or not record[name].strip():raise ValueError('invalid text field '+name)
   try:
    start=datetime.fromisoformat(record['startedAtUtc'].replace('Z','+00:00'));end=datetime.fromisoformat(record['completedAtUtc'].replace('Z','+00:00'))
    if start.tzinfo is None or end.tzinfo is None or end<start:raise ValueError('invalid experiment timestamps')
   except (TypeError,ValueError) as failure:raise ValueError('invalid experiment timestamps') from failure
   for name in ('title','expected','sourceCommit','sourceProvenanceReport','replayCommand','environment','dataVolume'):
    if not record.get(name):raise ValueError(f'missing {name} {ident}/{variant}')
   if not isinstance(record['sourceCommit'],str) or not re.fullmatch(r'[a-f0-9]{40,64}',record['sourceCommit']) or (expected_commit is not None and record['sourceCommit']!=expected_commit):raise ValueError('experiment source commit mismatch')
   if record['sourceProvenanceReport']!='run-summary.json' or record['replayCommand']!='python3 scripts/ci/run_pr19.py --fault '+ident:raise ValueError('invalid experiment replay/provenance link')
   if not isinstance(record['environment'],dict) or not all(record['environment'].get(k) for k in ('javaVersion','os','architecture')):raise ValueError('missing runtime environment')
   if not isinstance(record['dataVolume'],dict) or any(type(v) is not int or v<0 for v in record['dataVolume'].values()):raise ValueError('invalid data volume')
   counts=record.get('counts')
   if not isinstance(counts,dict) or set(counts)!=set(COUNTS) or any(type(counts[k]) is not int or counts[k]<0 for k in COUNTS):raise ValueError(f'invalid measured counts {ident}/{variant}')
   if ident=='F-08':
    processes=record.get('processes',[])
    if not isinstance(processes,list) or len(processes)<2 or any(not isinstance(p,dict) for p in processes):raise ValueError('missing multi-process actors')
    if any(not isinstance(p.get('ready'),dict) for p in processes):raise ValueError('missing process ready marker')
    pids=[p.get('javaPid') for p in processes];backend=[p.get('ready',{}).get('backendPid') for p in processes]
    if any(type(p) is not int or p<=0 for p in pids+backend) or len(set(pids))!=len(pids) or len(set(backend))!=len(backend):raise ValueError('actors must have distinct real JVM and backend PIDs')
    if record['observations'].get('executionMode')!='SEPARATE_JVMS_AND_POSTGRESQL_CONNECTIONS':raise ValueError('incorrect multi-process execution mode')
   if ident=='F-06':
    processes=record.get('processes',[record.get('processEvidence',{})])
    if not isinstance(processes,list) or not processes or any(not isinstance(p,dict) or type(p.get('javaPid')) is not int or p['javaPid']<=0 for p in processes):raise ValueError('missing crash process evidence')
    if variant=='lease-fencing-controlled':
     if record['observations'].get('controlledSample') is not True:raise ValueError('controlled lease sample must be labeled')
    elif not any(p.get('forceKilled') is True and type(p.get('exitCode')) is int and p['exitCode']!=0 for p in processes):raise ValueError('process crash must prove forcible termination')
   actual.append(record)
 return actual

def validate_suites(folder,fault):
 required=set(sum(CLASSES.values(),[])) if fault=='all' else set(CLASSES[fault]);seen=set();totals={k:0 for k in ('tests','failures','errors','skipped')}
 for file in sorted(folder.glob('TEST-*.xml')):
  try:report=ET.parse(file).getroot();name=report.attrib['name'].split('.')[-1]
  except (OSError,ET.ParseError,KeyError) as failure:raise ValueError('invalid JUnit report') from failure
  if name not in required:continue
  if name in seen or report.get('name')!=qualified(name):raise ValueError('duplicate or mismatched JUnit suite')
  if any(not re.fullmatch(r'\d+',report.get(k,'')) for k in totals):raise ValueError('explicit numeric JUnit counters required')
  values={k:int(report.get(k)) for k in totals};cases=report.findall('testcase')
  if values['tests']!=SUITE_CASE_COUNTS[name] or len(cases)!=values['tests'] or any(values[k] for k in ('failures','errors','skipped')):raise ValueError('PR19 suite incomplete, skipped or failed: '+name)
  identities=[(case.get('classname'),case.get('name')) for case in cases]
  if len(set(identities))!=len(cases) or any(c!=qualified(name) or not n for c,n in identities) or any(case.find(tag) is not None for case in cases for tag in ('failure','error','skipped')):raise ValueError('PR19 testcase coverage or outcome invalid')
  seen.add(name)
  for k,v in values.items():totals[k]+=v
 if seen!=required:raise ValueError('missing PR19 suites: '+','.join(sorted(required-seen)))
 return {'classes':sorted(seen),'totals':totals}
def outcome(code,valid):return 'PASS' if code==0 and valid else 'FAIL'

def capability_problem(environment):
 return [name for name in ('java','docker') if environment.get(name,{}).get('exitCode')!=0]


def aggregate(folder,output):
 report={'status':'FAIL','completedAtUtc':datetime.now(timezone.utc).isoformat(),'runs':[]}
 try:
  seen=set();commit=None;manifest=None
  for file in sorted(folder.rglob('run-summary.json')):
   try:run=json.loads(file.read_text())
   except (OSError,ValueError) as failure:raise ValueError('invalid matrix run report') from failure
   if not isinstance(run,dict):raise ValueError('matrix run must be an object')
   fault=run.get('fault')
   if not isinstance(fault,str) or fault not in VARIANTS or fault in seen:raise ValueError('invalid or repeated matrix fault')
   if run.get('status')!='PASS' or run.get('mavenExitCode')!=0 or run.get('inputDrift') is not False:raise ValueError('matrix child failed: '+fault)
   commit_record=run.get('commit');inputs=run.get('inputFilesSha256')
   if not isinstance(commit_record,dict):raise ValueError('invalid matrix commit provenance')
   identity=commit_record.get('output')
   if not isinstance(identity,str) or not re.fullmatch(r'[a-f0-9]{40,64}',identity):raise ValueError('invalid matrix commit')
   if not isinstance(inputs,dict) or not inputs or any(not isinstance(k,str) or not k or not isinstance(v,str) or not re.fullmatch(r'[a-f0-9]{64}',v) for k,v in inputs.items()):raise ValueError('invalid matrix input manifest')
   if commit is not None and (identity!=commit or inputs!=manifest):raise ValueError('matrix sources differ')
   commit=identity;manifest=inputs;seen.add(fault)
   experiments=validate_faults(file.parent,fault,identity);suites=validate_suites(file.parent.parent/'failsafe-reports',fault)
   report['runs'].append({'fault':fault,'variantCount':len(experiments),'junit':suites,'report':str(file),'elapsedSeconds':run.get('elapsedSeconds')})
  if seen!=set(VARIANTS):raise ValueError('matrix incomplete: '+','.join(sorted(set(VARIANTS)-seen)))
  report.update(status='PASS',commit=commit,variantCount=sum(r['variantCount'] for r in report['runs']),inputFilesSha256=manifest)
 except ValueError as failure:report['failure']=str(failure)
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(report,indent=2)+'\n')
 return 0 if report['status']=='PASS' else 1

def main(argv=None):
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--fault',choices=['all',*VARIANTS],default='all');parser.add_argument('--aggregate',type=Path);args=parser.parse_args(argv)
 if args.aggregate:return aggregate(args.aggregate,Path(__file__).resolve().parents[2]/'target/pr19/ci-matrix-summary.json')
 import os,platform
 root=Path(__file__).resolve().parents[2];folder=root/'target/pr19';env=test_environment(os.environ);command=build_command(args.fault);before=input_manifest(root)
 report={'status':'FAIL','fault':args.fault,'startedAtUtc':datetime.now(timezone.utc).isoformat(),'command':command,'commit':diagnostic(['git','rev-parse','HEAD'],root,env),'inputFilesSha256':before,'provider':'FAKE_ONLY','bankEvidence':'SIMULATED_BANK_EVIDENCE','realAccountProbesEnabled':False,'environment':{'os':platform.system(),'architecture':platform.machine(),'python':platform.python_version(),'cpuCount':os.cpu_count(),'java':diagnostic([str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java','-version'],root,env),'docker':diagnostic(['docker','version','--format','{{.Server.Version}}'],root,env)}}
 blocked=capability_problem(report['environment'])
 if blocked:
  report.update(status='BLOCKED',capabilityFailure=blocked,mavenExitCode=None,completedAtUtc=datetime.now(timezone.utc).isoformat())
  folder.mkdir(parents=True,exist_ok=True);(folder/'run-summary.json').write_text(json.dumps(report,indent=2)+'\n');print('PR19 BLOCKED: unavailable '+','.join(blocked));return 2
 start=time.monotonic();code=run_process(command,root,env,folder/'maven.txt');report['mavenExitCode']=code;report['elapsedSeconds']=round(time.monotonic()-start,3);report['completedAtUtc']=datetime.now(timezone.utc).isoformat();report['inputDrift']=before!=input_manifest(root)
 if code==0:
  try:
   report['experiments']=validate_faults(folder,args.fault,report['commit']['output']);report['junit']=validate_suites(root/'target/failsafe-reports',args.fault)
   if args.fault=='all':report['pr18Evidence']=validate_evidence(root/'target/pr18',100,100,180018)
   if report['inputDrift']:raise ValueError('source inputs changed during run')
   report['status']='PASS'
  except ValueError as failure:report['evidenceFailure']=str(failure);code=1
 folder.mkdir(parents=True,exist_ok=True);(folder/'run-summary.json').write_text(json.dumps(report,indent=2)+'\n')
 print(f"PR19 {report['status']}: exit={code}; elapsed={report['elapsedSeconds']}s; evidence={folder/'run-summary.json'}")
 return code
if __name__=='__main__':sys.exit(main())
