#!/usr/bin/env python3
"""Strict PR20 budgets and evidence gates. Isolated experiments only; never read .env."""
from pathlib import Path
import csv
import json
import math
import re
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import os
import subprocess
import sys
import time
import uuid
from run_pr18 import test_environment
from run_pr18 import input_manifest, diagnostic, run_process

FULL_BUDGET={'history':1000000,'warmupSeconds':300,'measureSeconds':1800,'writeRps':100,'queryRps':200}
SMOKE_BUDGET={'history':1000,'warmupSeconds':5,'measureSeconds':20,'writeRps':5,'queryRps':10}

def budget(profile):
    if profile not in ('full','smoke'):raise ValueError('profile must be full or smoke')
    return dict(FULL_BUDGET if profile=='full' else SMOKE_BUDGET)

def performance_command(profile,output,java,classpath):
    b=budget(profile)
    command=[java,'-Xms128m','-Xmx768m','-cp',classpath,'com.paymentsledger.pr20.performance.Pr20PerformanceExperiment']
    for option,key in (('history','history'),('warmup-seconds','warmupSeconds'),('measure-seconds','measureSeconds'),('write-rps','writeRps'),('query-rps','queryRps')):
        command.extend(['--'+option,str(b[key])])
    return command+['--seed','200020','--drain-seconds','120','--output',str(output)]

def structured(record,name):
    if not isinstance(record,dict) or not record:raise ValueError(name+' must be a nonempty object')
    return record

def require(record,fields):
    for field in fields:
        if field not in record:raise ValueError('missing evidence field: '+field)

def number(value,name,minimum=0,integer=False):
    if type(value) not in ((int,) if integer else (int,float)) or not math.isfinite(value) or value<minimum:
        raise ValueError(name+' must be a finite '+('integer' if integer else 'number'))
    return value

def counter(record,name):return number(record.get(name),name,integer=True)

def validate_source_binding(hashes,frozen):
    structured(hashes,'reported source hashes')
    for path,digest in hashes.items():
        if not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest) or frozen.get(path)!=digest:
            raise ValueError('reported source hash differs from frozen input: '+str(path))

def timestamp(value,name):
    if not isinstance(value,str):raise ValueError(name+' timestamp required')
    try:stamp=datetime.fromisoformat(value.replace('Z','+00:00'))
    except ValueError as e:raise ValueError(name+' invalid timestamp') from e
    if stamp.tzinfo is None:raise ValueError(name+' timezone required')
    return stamp

def resource_sample(sample,record):
    stamp=timestamp(sample.get('atUtc'),'resource sample')
    for field,keys in (('db',('numbackends','xact_commit','xact_rollback','database_bytes')),
                       ('provider',('calls','accepted','capture_effects'))):
        metrics=structured(sample.get(field),field)
        for key in keys:counter(metrics,key)
    queues=structured(sample.get('queues'),'resource queues')
    for key in ('outbox_event','delivery_task','recovery_task','balance_transaction'):
        queue=structured(queues.get(key),key);counter(queue,'count')
        number(queue.get('oldestageseconds',queue.get('oldestAgeSeconds')),'queue age')
    counter(queues,'uncompletedLoad');counter(sample,'kafkaLag')
    raw=sample.get('containerResources')
    if not isinstance(raw,str) or not raw.strip():raise ValueError('actual container resource rows required')
    containers=[json.loads(line) for line in raw.splitlines() if line.strip()]
    expected={record['environment']['postgresContainerId'],record['environment']['kafkaContainerId']}
    if len(containers)!=2 or {c.get('Container') for c in containers}!=expected:raise ValueError('owned container resource identities required')
    for container in containers:
        for field in ('CPUPerc','MemPerc'):
            value=container.get(field)
            if not isinstance(value,str) or not value.endswith('%'):raise ValueError('container '+field+' required')
            number(float(value[:-1]),field)
        for field in ('MemUsage','PIDs','BlockIO','NetIO'):
            if not isinstance(container.get(field),str) or not container[field].strip():raise ValueError('container '+field+' required')
        number(int(container['PIDs']),'container PIDs',1,True)
    return stamp

def queue_growth(before,end):
    fields={'outbox_event','delivery_task','recovery_task','balance_transaction','uncompletedLoad'}
    totals=[];counts=[]
    for state in (before,end):
        queues=structured(state.get('queues'),'individual queues')
        if set(queues)!=fields:raise ValueError('complete individual queues required')
        values={name:counter(queues[name],'count') if isinstance(queues[name],dict) else number(queues[name],name,integer=True) for name in fields}
        total=sum(values.values())
        if total!=counter(state,'totalQueue'):raise ValueError('queue total mismatch')
        totals.append(total);counts.append(values)
    return sum(max(0,counts[1][name]-counts[0][name]) for name in fields)+max(0,counter(end,'kafkaLag')-counter(before,'kafkaLag'))

def close(actual,expected,name):
    number(actual,name)
    if not math.isclose(actual,expected,rel_tol=1e-8,abs_tol=1e-6):raise ValueError(name+' does not match raw counters')

def validate_phase(record,seconds,b):
    structured(record,'phase')
    if counter(record,'seconds')!=seconds:raise ValueError('phase budget mismatch')
    if number(record.get('actualSeconds'),'actualSeconds')<seconds:raise ValueError('short actual phase duration')
    for kind,rate in (('Writes',b['writeRps']),('Queries',b['queryRps'])):
        if counter(record,'scheduled'+kind)!=seconds*rate:raise ValueError('arrival budget incomplete')
        counter(record,'attempted'+kind)
    if counter(record,'scheduled')!=record['scheduledWrites']+record['scheduledQueries']:raise ValueError('scheduled sum mismatch')
    if counter(record,'attempted')!=record['attemptedWrites']+record['attemptedQueries']:raise ValueError('attempted sum mismatch')
    if record['scheduled']!=record['attempted']+counter(record,'dropped'):raise ValueError('unrecorded request outcomes')
    if counter(record,'writesAccepted')>record['attemptedWrites'] or counter(record,'queriesOk')>record['attemptedQueries']:raise ValueError('accepted exceeds attempts')
    errors=counter(record,'errors')
    if errors!=record['attempted']-record['writesAccepted']-record['queriesOk']:raise ValueError('error counts mismatch')
    close(record.get('errorRate'),errors/max(1,record['attempted']),'errorRate')
    close(record.get('effectiveWriteArrivalRps'),record['attemptedWrites']/seconds,'effectiveWriteArrivalRps')
    close(record.get('effectiveQueryArrivalRps'),record['attemptedQueries']/seconds,'effectiveQueryArrivalRps')
    latencies=structured(record.get('latencyMs'),'latencyMs')
    for kind in ('write','query'):
        values=structured(latencies.get(kind),kind)
        ordered=[number(values.get(key),key) for key in ('p50','p95','p99')]
        if ordered!=sorted(ordered):raise ValueError('percentiles out of order')

PERFORMANCE_ZERO_COUNTERS=('trialBalanceFailures','independentProjectionDifferences','duplicateOrUnsealedJournals',
    'overRefundPayments','historyResent','currentLoadUnknown','totalQueue','missingLocalPostedEffects',
    'unexpectedLocalPostedEffects','missingMerchantEffects','unexpectedMerchantEffects','duplicateExternalCapturesPerPayment')

def validate_performance(record,profile,folder=None):
    structured(record,'performance')
    require(record,('schemaVersion','experiment','status','configuration','source','environment','actors','roleReadiness',
                    'seedVerification','warmup','measurement','measurementBefore','measurementEnd','measurementQueueGrowth',
                    'correctness','rawEvidence','resourceSampleCount','instrumentationErrors'))
    if record['schemaVersion']!=1 or record['experiment']!='performance':raise ValueError('unknown performance schema')
    b=budget(profile);configuration=structured(record['configuration'],'configuration')
    if any(type(configuration.get(key)) is not int or configuration[key]!=value for key,value in b.items()):raise ValueError('performance budget mismatch')
    if record.get('fullBudget') is not (profile=='full'):raise ValueError('full budget marker mismatch')
    if record['status'] not in (('PASS','FAIL') if profile=='full' else ('SMOKE_PASS','FAIL')):raise ValueError('smoke and full outcomes cannot be interchanged')
    source=structured(record['source'],'source')
    if not isinstance(source.get('commit'),str) or not re.fullmatch('[0-9a-f]{40}',source['commit']):raise ValueError('source commit required')
    if type(source.get('workingTreeDirty')) is not bool:raise ValueError('source dirty state required')
    hashes=structured(source.get('sourceFileSha256'),'performance source hashes')
    if any(not isinstance(v,str) or not re.fullmatch('[0-9a-f]{64}',v) for v in hashes.values()):raise ValueError('performance source hashes malformed')
    env=structured(record['environment'],'environment')
    if env.get('apiInstances')!=2 or env.get('workerInstances')!=2 or env.get('runtimeRole')!='payments_app':raise ValueError('two API/two restricted Worker JVMs required')
    for field in ('postgresImage','kafkaImage','postgresContainerId','kafkaContainerId','database','providerDatabase'):
        if not isinstance(env.get(field),str) or not env[field].strip():raise ValueError('environment '+field+' required')
    if env['database']==env['providerDatabase']:raise ValueError('separate durable provider database required')
    actors=record['actors'];ready=record['roleReadiness']
    names={'api-1','api-2','worker-1','worker-2'}
    if not isinstance(actors,list) or len(actors)!=4 or any(not isinstance(a,dict) for a in actors) or {a.get('name') for a in actors}!=names:raise ValueError('four named processes required')
    pids=[number(a.get('pid'),'pid',1,True) for a in actors]
    if len(set(pids))!=4:raise ValueError('four distinct process PIDs required')
    if not isinstance(ready,list) or len(ready)!=4 or any(not isinstance(a,dict) for a in ready) or {a.get('pid') for a in ready}!=set(pids):raise ValueError('readiness PID mismatch')
    roles={a['pid']:a['name'].split('-')[0] for a in actors}
    for item in ready:
        role=roles[item['pid']]
        if item.get('role')!=role or item.get('databaseRole')!='payments_app' or set(item.get('activeProfiles',[]))!={role,'pr20-performance'}:raise ValueError('actual application role mismatch')
        if item.get('preloadL2ScanExecuted') is not (role=='worker'):raise ValueError('historical production L2 eligibility check missing')
    seed=structured(record['seedVerification'],'seedVerification')
    if counter(seed,'paymentCount')!=b['history'] or counter(seed,'fullyAssociatedEscalatedCount')!=b['history'] or seed.get('constraintsAndTriggersEnabled') is not True:raise ValueError('complete constrained historical data required')
    counter(seed,'outboxCount')
    validate_phase(record['warmup'],b['warmupSeconds'],b);validate_phase(record['measurement'],b['measureSeconds'],b)
    before=structured(record['measurementBefore'],'before');end=structured(record['measurementEnd'],'end')
    growth=queue_growth(before,end)
    if counter(record,'measurementQueueGrowth')!=growth:raise ValueError('queue growth mismatch')
    measurement=record['measurement']
    close(record.get('acceptedThroughputRps'),measurement['writesAccepted']/b['measureSeconds'],'acceptedThroughputRps')
    close(record.get('completedBusinessThroughputRps'),max(0,counter(end,'businessCompleted')-counter(before,'businessCompleted'))/b['measureSeconds'],'completedBusinessThroughputRps')
    correctness=structured(record['correctness'],'correctness')
    for key in PERFORMANCE_ZERO_COUNTERS:counter(correctness,key)
    number(correctness.get('providerBalanceDifferenceMinor'),'providerBalanceDifferenceMinor',minimum=-math.inf)
    for key in ('providerCaptured','ledgerPostedPayments','merchantFulfilled','providerAmountMinor','ledgerCaptureAmountMinor'):counter(correctness,key)
    structured(correctness.get('coverage'),'coverage')
    if type(correctness.get('pass')) is not bool:raise ValueError('explicit correctness verdict required')
    if not isinstance(record['instrumentationErrors'],list):raise ValueError('instrumentation errors required')
    if counter(record,'resourceSampleCount')<1:raise ValueError('actual resource samples required')
    structured(record['rawEvidence'],'rawEvidence')
    if folder is not None:validate_performance_raw(record,Path(folder))
    if record['status'] in ('PASS','SMOKE_PASS'):
        if record.get('fourDistinctLiveJvmsVerified') is not True or record.get('fourDistinctLiveJvmsAtMeasurementEnd') is not True:raise ValueError('live process proof required')
        if counter(record,'finalKafkaLag') or record.get('drainCompleted') is not True:raise ValueError('undrained workload cannot pass')
        if growth or measurement['dropped'] or measurement['errorRate']>=.001 or record['instrumentationErrors']:raise ValueError('performance failure hidden by PASS')
        if measurement['writesAccepted']==0 or measurement['queriesOk']==0:raise ValueError('non-vacuous measurement required')
        if any(measurement['latencyMs'][kind]['p95']>300 or measurement['latencyMs'][kind]['p99']>1000 for kind in ('write','query')):raise ValueError('latency failure hidden by PASS')
        if correctness['pass'] is not True or any(correctness[key] for key in PERFORMANCE_ZERO_COUNTERS) or correctness['providerBalanceDifferenceMinor']!=0:raise ValueError('financial failure hidden by PASS')
        if not correctness['providerCaptured'] or correctness['providerCaptured']!=correctness['ledgerPostedPayments'] or correctness['providerCaptured']!=correctness['merchantFulfilled'] or correctness['providerAmountMinor']!=correctness['ledgerCaptureAmountMinor']:raise ValueError('external/local completion mismatch')
    return record

def validate_performance_raw(record,folder):
    phases={name:{'scheduled':0,'attempted':0,'dropped':0,'errors':0,'writesAccepted':0,'queriesOk':0,
                  'scheduledWrites':0,'scheduledQueries':0,'attemptedWrites':0,'attemptedQueries':0,
                  'write':[],'query':[]} for name in ('warmup','measurement')}
    with (folder/'requests.csv').open(newline='') as stream:
        for row in csv.DictReader(stream):
            if row['phase'] not in phases or row['kind'] not in ('write','query'):raise ValueError('unknown raw request class')
            phase=phases[row['phase']];kind=row['kind'];suffix='Writes' if kind=='write' else 'Queries'
            phase['scheduled']+=1;phase['scheduled'+suffix]+=1
            if row['outcome']=='DROPPED':phase['dropped']+=1;continue
            phase['attempted']+=1;phase['attempted'+suffix]+=1
            actual=int(row['actualStartNanos']);finished=int(row['finishedNanos'])
            if finished<actual or actual<=0:raise ValueError('invalid raw request clock')
            latency=(finished-actual)/1e6;close(float(row['latencyMs']),latency,'raw latency')
            phase[kind].append(latency)
            if row['outcome']=='OK':
                if not 200<=int(row['status'])<300 or not row['resourceId']:raise ValueError('invalid successful raw request')
                phase['writesAccepted' if kind=='write' else 'queriesOk']+=1
            else:phase['errors']+=1
    for name,phase in phases.items():
        for field in ('scheduled','attempted','dropped','errors','writesAccepted','queriesOk','scheduledWrites','scheduledQueries','attemptedWrites','attemptedQueries'):
            if phase[field]!=record[name][field]:raise ValueError('raw '+name+' '+field+' mismatch')
        for kind in ('write','query'):
            values=sorted(phase[kind])
            for percentile in (50,95,99):
                expected=values[math.ceil(len(values)*percentile/100)-1] if values else 0.
                close(record[name]['latencyMs'][kind]['p'+str(percentile)],expected,'raw percentile')
    samples=[json.loads(line) for line in (folder/'samples.ndjson').read_text().splitlines() if line]
    if len(samples)!=record['resourceSampleCount']:raise ValueError('raw resource sample count mismatch')
    passing=record['status'] in ('PASS','SMOKE_PASS')
    stamps=[]
    for sample in samples:
        if not isinstance(sample,dict):raise ValueError('invalid resource sample')
        if passing:
            stamps.append(resource_sample(sample,record))
            if sample.get('instrumentationError'):raise ValueError('raw instrumentation failure hidden by PASS')
    if passing:
        start=timestamp(record.get('measurementStartedAtUtc'),'measurement start');end=timestamp(record.get('measurementEndedAtUtc'),'measurement end')
        if end<start or (end-start).total_seconds()<record['configuration']['measureSeconds']:raise ValueError('invalid measurement window')
        if not stamps or min(stamps)>start+timedelta(seconds=10) or max(stamps)<end-timedelta(seconds=10):raise ValueError('resources do not cover measurement')
    guards=[json.loads(line) for line in (folder/'disk-guards.ndjson').read_text().splitlines() if line]
    if not guards:raise ValueError('disk guard observations required')
    if passing:
        docker_observed=False
        for guard in guards:
            timestamp(guard.get('atUtc'),'disk guard')
            for key in ('hostUsableBytes','hostTotalBytes','reserveBytes'):number(guard.get(key),key,1,True)
            if guard['hostUsableBytes']<guard['reserveBytes']:raise ValueError('host reserve breached')
            if 'dockerDataUsableBytes' in guard:
                docker_observed=True
                for key in ('dockerDataUsableBytes','dockerDataTotalBytes'):number(guard.get(key),key,1,True)
                if guard['dockerDataUsableBytes']<guard['reserveBytes']:raise ValueError('Docker reserve breached')
        if not docker_observed:raise ValueError('Docker filesystem observations required')
    for actor in record['actors']:
        directory=folder/actor['name']
        actual_ready=json.loads((directory/'ready.json').read_text())
        if actual_ready not in record['roleReadiness']:raise ValueError('raw readiness mismatch')
        jvm=[json.loads(line) for line in (directory/'jvm.ndjson').read_text().splitlines() if line]
        if not jvm or any(item.get('pid')!=actor['pid'] for item in jvm):raise ValueError('actual per-process JVM resources required')
        for item in jvm:
            require(item,('heapUsed','heapMax','gcCount','gcMillis','processCpuNanos'))
            if passing:
                timestamp(item.get('at'),'JVM resource')
                for key in ('heapUsed','gcCount','gcMillis','processCpuNanos'):counter(item,key)
                number(item.get('heapMax'),'heapMax',1,True)
                if item['heapUsed']>item['heapMax']:raise ValueError('invalid heap observation')

RECOVERY_GATES={'ISOLATION','ARCHIVE','PERMISSIONS','REBUILD','TRIAL_BALANCE','EVENT_COVERAGE',
    'EXTERNAL_CONTINUITY','BT_COVERAGE','UNKNOWN_DISPOSITION','OUTBOX_REPLAY','INDEPENDENT_BALANCE','MANUAL_LOSS_DISPOSITION'}
DEMO_CASES={'DEMO-A-SUCCESS-BANK','DEMO-A-PAYOUT-RETURN','DEMO-B-REFUND-FAIL-LATE-WIN',
    'DEMO-UNKNOWN','DEMO-L3','DEMO-DIFFERENCE'}

def validate_cases(record,expected):
    cases=record.get('cases')
    if not isinstance(cases,list) or len(cases)!=len(expected) or any(not isinstance(case,dict) for case in cases):raise ValueError('complete case reports required')
    if {case.get('id') for case in cases}!=expected or any(case.get('status')!='PASS' for case in cases):raise ValueError('required cases must pass exactly once')
    if record.get('completeCaseSet') is not True:raise ValueError('partial run cannot pass')
    if record.get('status')!='PASS':raise ValueError('experiment did not pass')
    if not isinstance(record.get('sourceCommit'),str) or not re.fullmatch('[0-9a-f]{40}',record['sourceCommit']):raise ValueError('actual source commit required')
    hashes=structured(record.get('sourceInputSha256'),'sourceInputSha256')
    if any(not isinstance(value,str) or not re.fullmatch('[0-9a-f]{64}',value) for value in hashes.values()):raise ValueError('source input hashes required')
    if record.get('providerMode')!='PERSISTENT_FAKE' or record.get('bankEvidence')!='SIMULATED_BANK_EVIDENCE':raise ValueError('explicit isolated evidence source required')
    structured(record.get('environment'),'environment')

def validate_recovery(record,folder=None):
    structured(record,'recovery');require(record,('status','sourceCommit','backup','isolation','externalState','gates','rpo','rto','manualSteps','actualCommands'))
    validate_cases(record,{'RECOVERY-ARCHIVE-EXTERNAL-CONTINUITY'})
    if record.get('failure') or record.get('evidenceCaptureFailure'):raise ValueError('recovery evidence capture failed')
    for field in ('backup','isolation','externalState','gates','rpo','rto'):structured(record[field],field)
    external=record['externalState']
    if any(external.get(key) is not True for key in ('preserved','postBackupLossExercised','noDuplicateEffects')):raise ValueError('external facts and post-backup divergence must survive restoration')
    if not isinstance(external.get('effectsBefore'),list) or not external['effectsBefore'] or external.get('effectsAfter')!=external['effectsBefore']:raise ValueError('external effect identity continuity required')
    gates=record['gates'];scope=structured(gates.get('releaseScope'),'releaseScope')
    if gates.get('writeGateReleased') is not True or gates.get('globalWritesReleased') is not False or gates.get('releaseMode')!='ELIGIBLE_OPERATIONS_ONLY':raise ValueError('only qualified operations may resume funds execution')
    evidence=structured(gates.get('evidence'),'release evidence')
    if set(evidence)!=RECOVERY_GATES or any(value is not True for value in evidence.values()):raise ValueError('all independent financial release gates required')
    if scope.get('mode')!='ELIGIBLE_OPERATIONS_ONLY' or scope.get('globalWritesReleased') is not False:raise ValueError('unrestricted global funds release is forbidden')
    for key in ('releasedOperationIds','excludedOperationIds','excludedManualRecords'):
        if not isinstance(scope.get(key),list) or not scope[key] or any(not isinstance(value,str) or not value for value in scope[key]):raise ValueError('explicit eligible/excluded work required')
    if set(scope['releasedOperationIds'])&set(scope['excludedOperationIds']):raise ValueError('expired unknown cannot be released')
    checks=gates.get('financialChecks')
    if not isinstance(checks,list) or len(checks)!=7 or any(not isinstance(check,dict) for check in checks):raise ValueError('all seven reconciliation checks required')
    checks={check.get('check_number'):check.get('outcome') for check in checks}
    if set(checks)!={1,2,3,4,5,6,7} or any(checks[n]!='PASS' for n in (1,3,4,5,6,7)) or checks[2]!='INCOMPLETE':raise ValueError('financial checks and held operational backlog must remain distinct')
    if not str(gates.get('operationalStatus','')).startswith('INCOMPLETE'):raise ValueError('unresolved operational disposition required')
    isolation=record['isolation'];source=structured(isolation.get('source'),'source isolation');target=structured(isolation.get('target'),'target isolation')
    if isolation.get('newDistinctTarget') is not True or isolation.get('userDatabaseAccess') is not False or source.get('containerId')==target.get('containerId') or source.get('database')==target.get('database'):raise ValueError('restore must use a distinct new isolated database')
    if not str(source.get('database','')).startswith('pr20_source_') or not str(target.get('database','')).startswith('pr20_restore_'):raise ValueError('explicit disposable database identities required')
    backup=record['backup']
    if counter(backup,'bytes')<1 or not isinstance(backup.get('sha256'),str) or not re.fullmatch('[0-9a-f]{64}',backup['sha256']):raise ValueError('binary backup digest required')
    for key in ('cutoff','snapshot','format'):
        if not isinstance(backup.get(key),str) or backup[key] in ('','NOT_RUN'):raise ValueError('consistent backup cutoff required')
    for field in ('rpo','rto'):
        counter(record[field],'measuredMillis')
        if not isinstance(record[field].get('definition'),str) or not record[field]['definition'].strip():raise ValueError('RPO/RTO definition required')
    if not isinstance(record['manualSteps'],list) or not record['manualSteps'] or not isinstance(record.get('unrecoverableRecords'),list) or not record['unrecoverableRecords']:raise ValueError('non-automatic loss and manual actions required')
    assertions=record.get('assertionResults')
    if not isinstance(assertions,list) or len(assertions)<12 or any(not isinstance(item,dict) or item.get('passed') is not True for item in assertions):raise ValueError('actual independent assertions required')
    if not isinstance(record['actualCommands'],list) or len(record['actualCommands'])<3:raise ValueError('actual backup restore and replay commands required')
    if folder is not None:
        folder=Path(folder);archive=folder/'consistent-backup.dump'
        if archive.stat().st_size!=backup['bytes'] or hashlib.sha256(archive.read_bytes()).hexdigest()!=backup['sha256']:raise ValueError('raw archive integrity mismatch')
        for name in ('pg_dump.log','pg_restore.log'):
            if not (folder/name).read_text().startswith('exitCode=0\n'):raise ValueError('actual backup/restore exit code required')
        before_path=folder/'external-state-before-restore.json';after_path=folder/'provider-state.json'
        if hashlib.sha256(before_path.read_bytes()).hexdigest()!=external.get('beforeSha256'):raise ValueError('raw external snapshot checksum mismatch')
        rescan=structured(record.get('rescan'),'scoped rescan')
        if rescan.get('livemode') is not False:raise ValueError('isolated test scope required')
        scope='/'.join(str(rescan.get(key)) for key in ('organizationId','environmentId','providerAccountId'))+'/false'
        for path,effects in ((before_path,external['effectsBefore']),(after_path,external['effectsAfter'])):
            state=json.loads(path.read_text())
            if not isinstance(state,dict) or state.get('scopes',{}).get(scope,{}).get('effects')!=effects:raise ValueError('durable external effect observations mismatch')
        for field in ('restoredIndependentAudit','finalIndependentAudit','independentAudit'):validate_rebuild(record.get(field))
        if record.get('backupSnapshotCounts')!=record.get('restoredSnapshotCounts'):raise ValueError('consistent restored snapshot counts mismatch')
    return record

def validate_rebuild(audit):
    structured(audit,'independent audit')
    if counter(audit,'invalidJournals') or counter(audit,'eventCoverageFailures'):raise ValueError('independent journal coverage required')
    accounts=structured(audit.get('rebuild'),'rebuild').get('accounts')
    if not isinstance(accounts,list) or not accounts or any(not isinstance(account,dict) for account in accounts):raise ValueError('actual rebuilt account observations required')
    if len({account.get('accountId') for account in accounts})!=len(accounts):raise ValueError('duplicate rebuilt account')
    total=0;entries=0
    for account in accounts:
        rebuilt=number(account.get('rebuilt'),'rebuilt',minimum=-math.inf);projected=number(account.get('projected'),'projected',minimum=-math.inf)
        if number(account.get('residual'),'residual',minimum=-math.inf)!=0 or rebuilt!=projected:raise ValueError('independent balance residual')
        scanned=counter(account,'scannedEntries');entries+=scanned;total+=rebuilt
        if scanned and account.get('projectionPresent') is not True:raise ValueError('missing projection for scanned entries')
    if not entries or total!=0:raise ValueError('non-vacuous independent trial balance required')

def validate_demo(record,folder=None):
    structured(record,'demo');require(record,('status','sourceCommit','cases','environment','providerMode','bankEvidence'))
    validate_cases(record,DEMO_CASES)
    if folder is not None:
        folder=Path(folder)
        for case in record['cases']:
            report=json.loads((folder/(case['id']+'.json')).read_text())
            if report.get('caseId')!=case['id'] or report.get('status')!='PASS' or report.get('sourceCommit')!=record['sourceCommit']:raise ValueError('raw demo case identity mismatch')
            if report.get('evidenceCaptureFailure') or report.get('failure'):raise ValueError('raw demo evidence failure')
            validate_rebuild(report.get('independentAudit'))
            structured(report.get('snapshot'),'actual demo snapshot')
            if not (folder/case['id']/'provider-state.json').is_file():raise ValueError('durable raw demo provider state required')
    return record

def run_outcome(exit_code,valid):return 'PASS' if exit_code==0 and valid else 'FAIL'

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase',choices=('all','performance','recovery','demo'),default='all')
    parser.add_argument('--profile',choices=('full','smoke'),default='full')
    args=parser.parse_args(argv)
    root=Path(__file__).resolve().parents[2]
    run_id=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]
    folder=root/'target/pr20/runner'/run_id
    environ=test_environment(os.environ)
    java=str(Path(environ['JAVA_HOME'])/'bin/java') if environ.get('JAVA_HOME') else 'java'
    before=input_manifest(root)
    source=diagnostic(['git','rev-parse','HEAD'],root,environ)
    report={'schemaVersion':1,'runId':run_id,'phase':args.phase,'profile':args.profile,'status':'FAIL',
            'startedAtUtc':datetime.now(timezone.utc).isoformat(),'source':source,'inputFilesSha256':before,
            'providerMode':'SIMULATORS_ONLY','realAccountProbesEnabled':False,'budget':budget(args.profile),
            'environment':{'java':diagnostic([java,'-version'],root,environ),
                           'docker':diagnostic(['docker','version','--format','{{.Server.Version}}'],root,environ)},
            'commands':[],'experiments':{},'failures':[]}
    started=time.monotonic()
    def execute(command,label):
        log=folder/(label+'.txt');stamp=time.monotonic()
        code=run_process(command,root,environ,log)
        report['commands'].append({'command':command,'exitCode':code,'elapsedSeconds':round(time.monotonic()-stamp,3),'log':str(log)})
        return code
    def collect(domain,validator):
        evidence=root/'target/pr20'/domain/run_id
        try:
            record=json.loads((evidence/'summary.json').read_text());validator(record,evidence)
            observed=record.get('source',{}).get('commit') if domain=='performance' else record.get('sourceCommit')
            if observed!=source.get('output'):raise ValueError('experiment source commit differs from runner')
            hashes=record['source']['sourceFileSha256'] if domain=='performance' else record['sourceInputSha256']
            validate_source_binding(hashes,before)
            report['experiments'][domain]={'status':record['status'],'report':str(evidence/'summary.json'),'rawEvidenceValidated':True}
            return record['status'] in ('PASS','SMOKE_PASS')
        except (ValueError,OSError,KeyError,TypeError) as failure:
            # Keep partial FAIL/BLOCKED summaries and their original artifacts; never manufacture missing evidence.
            observed=None
            try:observed=json.loads((evidence/'summary.json').read_text()).get('status')
            except (ValueError,OSError):pass
            report['experiments'][domain]={'status':'FAIL','observedStatus':observed,'report':str(evidence/'summary.json'),'rawEvidenceValidated':False,'failure':str(failure)}
            report['failures'].append(domain+': '+str(failure));return False
    ok=True
    try:
        if source.get('exitCode')!=0 or not re.fullmatch('[0-9a-f]{40}',source.get('output','')):raise ValueError('cannot establish actual source commit')
        if report['environment']['java'].get('exitCode')!=0 or not re.search(r'version "21[.\"]',report['environment']['java'].get('output','')):raise ValueError('Java21 is required')
        if report['environment']['docker'].get('exitCode')!=0:raise ValueError('Docker engine is required')
        if args.phase in ('all','recovery','demo'):
            command=['./mvnw','-B']+(['clean'] if args.phase=='all' else [])+['verify','-Dpr20.runId='+run_id,'-Dpr20.sourceCommit='+source['output']]
            if args.phase!='all':
                command+=['-Dit.test='+('Pr20RecoveryIT' if args.phase=='recovery' else 'Pr20DemoIT'),'-Dtest=Pr20RecoveryGateTest']
            ok=execute(command,'verification')==0
            for domain in (('recovery','demo') if args.phase=='all' else (args.phase,)):
                ok=collect(domain,validate_recovery if domain=='recovery' else validate_demo) and ok
        if args.phase in ('all','performance') and ok:
            classpath_file=root/'target/pr20-test-classpath.txt'
            command=['./mvnw','-B','-DskipTests','test-compile','dependency:build-classpath','-Dmdep.outputFile='+str(classpath_file)]
            if execute(command,'performance-compile')!=0:raise ValueError('performance test compilation failed')
            classpath=str(root/'target/test-classes')+os.pathsep+str(root/'target/classes')+os.pathsep+classpath_file.read_text().strip()
            command=performance_command(args.profile,root/'target/pr20/performance'/run_id,java,classpath)
            code=execute(command,'performance')
            valid=collect('performance',lambda record,path:validate_performance(record,args.profile,path))
            ok=(code==0 and valid) and ok
        elif args.phase=='all':
            report['experiments']['performance']={'status':'NOT_RUN','reason':'verification or recovery/demo evidence failed before performance phase'}
    except (OSError,ValueError,KeyError,TypeError) as failure:
        report['failures'].append(str(failure));ok=False
    finally:
        report['inputDrift']=before!=input_manifest(root)
        if report['inputDrift']:report['failures'].append('tested source inputs changed during run');ok=False
        report['status']=('PASS' if args.profile=='full' else 'SMOKE_PASS') if ok else 'FAIL'
        report['elapsedSeconds']=round(time.monotonic()-started,3)
        report['completedAtUtc']=datetime.now(timezone.utc).isoformat()
        folder.mkdir(parents=True,exist_ok=True)
        (folder/'run-summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print('PR20 '+report['status']+': '+str(folder/'run-summary.json'))
    return 0 if ok else 1

if __name__=='__main__':sys.exit(main())
