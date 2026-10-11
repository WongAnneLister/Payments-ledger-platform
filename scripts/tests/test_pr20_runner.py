import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'ci'))
import run_pr20 as r

def performance_report(profile='full'):
    b=r.budget(profile)
    def phase(seconds):
        writes=seconds*b['writeRps'];queries=seconds*b['queryRps']
        return {'seconds':seconds,'actualSeconds':seconds,'scheduledWrites':writes,'scheduledQueries':queries,
                'attemptedWrites':writes,'attemptedQueries':queries,'scheduled':writes+queries,'attempted':writes+queries,
                'dropped':0,'errors':0,'errorRate':0.,'writesAccepted':writes,'queriesOk':queries,
                'effectiveWriteArrivalRps':b['writeRps'],'effectiveQueryArrivalRps':b['queryRps'],
                'latencyMs':{kind:{'p50':10.,'p95':20.,'p99':30.} for kind in ('write','query')}}
    actors=[{'name':name,'pid':100+i,'port':9000+i,'directory':'/fixture/'+name}
            for i,name in enumerate(('api-1','api-2','worker-1','worker-2'))]
    zero=('trialBalanceFailures','independentProjectionDifferences','duplicateOrUnsealedJournals',
          'overRefundPayments','historyResent','currentLoadUnknown','totalQueue','missingLocalPostedEffects',
          'unexpectedLocalPostedEffects','missingMerchantEffects','unexpectedMerchantEffects','duplicateExternalCapturesPerPayment')
    effects=(b['warmupSeconds']+b['measureSeconds'])*b['writeRps']
    correctness={key:0 for key in zero}
    correctness.update(pass_=True)
    correctness['pass']=correctness.pop('pass_')
    correctness.update(providerCaptured=effects,ledgerPostedPayments=effects,merchantFulfilled=effects,
                       providerAmountMinor=effects*100,ledgerCaptureAmountMinor=effects*100,providerBalanceDifferenceMinor=0,
                       coverage={'captureEffects':effects,'journalEntryCount':effects*6,'accountProjectionCount':4,'refundEffects':0},
                       providerCallsByKind=[{'kind':'CREATE','calls':effects}])
    queues={key:{'count':0,'oldestAgeSeconds':0.} for key in ('outbox_event','delivery_task','recovery_task','balance_transaction')}
    queues['uncompletedLoad']=0
    return {'schemaVersion':1,'experiment':'performance','status':'PASS' if profile=='full' else 'SMOKE_PASS',
            'configuration':dict(b,seed=200020,drainSeconds=120,output='/fixture'),'fullBudget':profile=='full',
            'source':{'commit':'a'*40,'workingTreeDirty':False,'invocation':'fixture','sourceFileSha256':{'src/main/fixture.java':'b'*64}},
            'environment':{'apiInstances':2,'workerInstances':2,'runtimeRole':'payments_app','postgresImage':'postgres:16',
                           'kafkaImage':'apache/kafka:3.9.0','postgresContainerId':'pg-fixture','kafkaContainerId':'kafka-fixture',
                           'database':'pr20_performance_platform','providerDatabase':'pr20_external_provider'},
            'actors':actors,'roleReadiness':[{'pid':actor['pid'],'role':actor['name'].split('-')[0],
                  'databaseRole':'payments_app','activeProfiles':[actor['name'].split('-')[0],'pr20-performance'],
                  'preloadL2ScanExecuted':actor['name'].startswith('worker')} for actor in actors],
            'fourDistinctLiveJvmsVerified':True,'fourDistinctLiveJvmsAtMeasurementEnd':True,
            'seedVerification':{'paymentCount':b['history'],'fullyAssociatedEscalatedCount':b['history'],
                                'outboxCount':0,'constraintsAndTriggersEnabled':True},
            'warmup':phase(b['warmupSeconds']),'measurement':phase(b['measureSeconds']),
            'measurementBefore':{'totalQueue':0,'queues':copy.deepcopy(queues),'kafkaLag':0,'businessCompleted':b['warmupSeconds']*b['writeRps']},
            'measurementEnd':{'totalQueue':0,'queues':copy.deepcopy(queues),'kafkaLag':0,'businessCompleted':effects},
            'finalKafkaLag':0,'drainCompleted':True,'measurementQueueGrowth':0,'acceptedThroughputRps':b['writeRps'],'completedBusinessThroughputRps':b['writeRps'],
            'correctness':correctness,'resourceSampleCount':(b['warmupSeconds']+b['measureSeconds'])//5,
            'instrumentationErrors':[],'rawEvidence':{'requestsCsv':'requests.csv','samplesNdjson':'samples.ndjson',
                                                   'diskGuardsNdjson':'disk-guards.ndjson'}}

class PerformanceContractTests(unittest.TestCase):
    def test_complete_full_metadata_is_accepted(self):
        self.assertEqual('PASS',r.validate_performance(performance_report(),'full')['status'])
    def test_complete_smoke_metadata_is_accepted_only_as_smoke(self):
        self.assertEqual('SMOKE_PASS',r.validate_performance(performance_report('smoke'),'smoke')['status'])
    def reject(self,mutate):
        report=performance_report();mutate(report)
        with self.assertRaises(ValueError):r.validate_performance(report,'full')
    def test_wrong_seed_count(self):self.reject(lambda x:x['seedVerification'].update(paymentCount=999999))
    def test_short_actual_measurement(self):self.reject(lambda x:x['measurement'].update(actualSeconds=10))
    def test_full_marker_cannot_override_budget(self):self.reject(lambda x:x['configuration'].update(measureSeconds=20))
    def test_duplicate_actor_pid(self):self.reject(lambda x:x['actors'][1].update(pid=100))
    def test_admin_application_role(self):self.reject(lambda x:x['roleReadiness'][0].update(databaseRole='pr20_admin'))
    def test_worker_readiness_without_l2_scan(self):self.reject(lambda x:x['roleReadiness'][2].update(preloadL2ScanExecuted=False))
    def test_boolean_cannot_be_a_numeric_counter(self):self.reject(lambda x:x['measurement'].update(errors=False))
    def test_nan_cannot_be_a_latency(self):self.reject(lambda x:x['measurement']['latencyMs']['write'].update(p95=float('nan')))
    def test_missing_queries(self):self.reject(lambda x:x['measurement'].update(queriesOk=0))
    def test_error_rate_computation_must_match_counters(self):self.reject(lambda x:x['measurement'].update(errors=100,errorRate=0.))
    def test_queue_growth_cannot_be_hidden_by_pass(self):self.reject(lambda x:x.update(measurementQueueGrowth=1))
    def test_bad_percentile_cannot_be_hidden_by_pass(self):self.reject(lambda x:x['measurement']['latencyMs']['query'].update(p95=301.))
    def test_missing_independent_rebuild(self):self.reject(lambda x:x['correctness'].pop('independentProjectionDifferences'))
    def test_duplicate_provider_effect_is_rejected(self):self.reject(lambda x:x['correctness'].update(duplicateExternalCapturesPerPayment=1))
    def test_no_actual_resource_samples(self):self.reject(lambda x:x.update(resourceSampleCount=0))
    def test_instrumentation_failure_is_not_a_pass(self):self.reject(lambda x:x.update(instrumentationErrors=['MISSING_JVM_SAMPLE']))
    def test_complete_target_failure_remains_failure(self):
        report=performance_report();report['status']='FAIL';report['measurement']['latencyMs']['write'].update(p95=301.,p99=400.)
        self.assertEqual('FAIL',r.validate_performance(report,'full')['status'])
    def test_rotating_backlog_cannot_cancel_individual_queue_growth(self):
        report=performance_report();report['measurementBefore']['queues']['outbox_event']['count']=10
        report['measurementBefore']['totalQueue']=10;report['measurementEnd']['queues']['balance_transaction']['count']=10
        report['measurementEnd']['totalQueue']=10
        self.assertEqual(10,r.queue_growth(report['measurementBefore'],report['measurementEnd']))
        with self.assertRaises(ValueError):r.validate_performance(report,'full')
    def test_signed_independent_residual_is_retained_as_failure(self):
        report=performance_report();report['status']='FAIL';report['correctness']['providerBalanceDifferenceMinor']=-1
        self.assertEqual(-1,r.validate_performance(report,'full')['correctness']['providerBalanceDifferenceMinor'])

def case_report(ids):
    return {'status':'PASS','completeCaseSet':True,'sourceCommit':'a'*40,'sourceInputSha256':{'src/main/fixture.java':'b'*64},
            'sourceDirty':False,'environment':{'postgresImage':'postgres:16'},'providerMode':'PERSISTENT_FAKE',
            'bankEvidence':'SIMULATED_BANK_EVIDENCE','cases':[{'id':value,'status':'PASS'} for value in sorted(ids)]}

def recovery_report():
    report=case_report({'RECOVERY-ARCHIVE-EXTERNAL-CONTINUITY'})
    report.update(backup={'path':'consistent-backup.dump','sha256':'b'*64,'bytes':100,'cutoff':'2026-10-11T00:00:00Z',
                          'snapshot':'123','format':'PG_DUMP_CUSTOM_EXPORTED_REPEATABLE_READ_SNAPSHOT'},
                  isolation={'newDistinctTarget':True,'userDatabaseAccess':False,
                             'source':{'containerId':'source','database':'pr20_source_fixture'},
                             'target':{'containerId':'target','database':'pr20_restore_fixture'}},
                  externalState={'preserved':True,'postBackupLossExercised':True,'noDuplicateEffects':True,
                                 'effectsBefore':['charge:fixture','refund:fixture'],'effectsAfter':['charge:fixture','refund:fixture']},
                  gates={'writeGateReleased':True,'globalWritesReleased':False,'releaseMode':'ELIGIBLE_OPERATIONS_ONLY',
                         'evidence':{key:True for key in r.RECOVERY_GATES},
                         'releaseScope':{'mode':'ELIGIBLE_OPERATIONS_ONLY','globalWritesReleased':False,
                                         'releasedOperationIds':['eligible'],'excludedOperationIds':['expired'],
                                         'excludedManualRecords':['lost-order']},
                         'operationalStatus':'INCOMPLETE: expired unknown',
                         'financialChecks':[{'check_number':n,'outcome':'INCOMPLETE' if n==2 else 'PASS'} for n in range(1,8)]},
                  rpo={'definition':'cutoff to loss wall time','measuredMillis':1000},
                  rto={'definition':'new target to qualified release monotonic time','measuredMillis':2000},
                  manualSteps=['Investigate expired unknown'],unrecoverableRecords=[{'id':'lost-order'}],
                  actualCommands=[['pg_dump'],['pg_restore'],'JUnit'],
                  assertionResults=[{'name':str(n),'passed':True,'expected':1,'actual':1} for n in range(12)])
    return report

class RecoveryContractTests(unittest.TestCase):
    def test_complete_scoped_restore_is_accepted(self):self.assertEqual('PASS',r.validate_recovery(recovery_report())['status'])
    def reject(self,mutate):
        report=recovery_report();mutate(report)
        with self.assertRaises(ValueError):r.validate_recovery(report)
    def test_global_release_is_rejected(self):self.reject(lambda x:x['gates'].update(globalWritesReleased=True))
    def test_missing_gate_is_rejected(self):self.reject(lambda x:x['gates']['evidence'].pop('BT_COVERAGE'))
    def test_false_gate_is_rejected(self):self.reject(lambda x:x['gates']['evidence'].update(INDEPENDENT_BALANCE=False))
    def test_expired_work_is_not_released(self):self.reject(lambda x:x['gates']['releaseScope'].update(releasedOperationIds=['expired']))
    def test_external_fact_removed(self):self.reject(lambda x:x['externalState'].update(effectsAfter=[]))
    def test_same_restore_target_is_rejected(self):self.reject(lambda x:x['isolation']['target'].update(containerId='source'))
    def test_predicted_rto_is_not_actual(self):self.reject(lambda x:x['rto'].update(measuredMillis='target 120seconds'))
    def test_manual_loss_cannot_be_erased(self):self.reject(lambda x:x.update(unrecoverableRecords=[]))
    def test_failed_financial_check_cannot_pass(self):self.reject(lambda x:x['gates']['financialChecks'][4].update(outcome='FAIL'))
    def test_operational_incomplete_is_not_hidden(self):self.reject(lambda x:x['gates']['financialChecks'][1].update(outcome='PASS'))
    def test_evidence_capture_failure_cannot_pass(self):self.reject(lambda x:x.update(evidenceCaptureFailure='missing snapshot'))

class DemoContractTests(unittest.TestCase):
    def test_all_six_cases_are_required(self):self.assertEqual('PASS',r.validate_demo(case_report(r.DEMO_CASES))['status'])
    def test_partial_case_set_is_rejected(self):
        report=case_report(r.DEMO_CASES);report['cases'].pop()
        with self.assertRaises(ValueError):r.validate_demo(report)
    def test_failed_difference_detection_cannot_pass(self):
        report=case_report(r.DEMO_CASES)
        next(case for case in report['cases'] if case['id']=='DEMO-DIFFERENCE')['status']='FAIL'
        with self.assertRaises(ValueError):r.validate_demo(report)
    def test_independent_rebuild_rejects_empty_accounts(self):
        with self.assertRaises(ValueError):r.validate_rebuild({'invalidJournals':0,'eventCoverageFailures':0,'rebuild':{'accounts':[]}})
    def test_independent_rebuild_uses_raw_balances(self):
        audit={'invalidJournals':0,'eventCoverageFailures':0,'rebuild':{'accounts':[
            {'accountId':'a','rebuilt':100,'projected':100,'residual':0,'scannedEntries':1,'projectionPresent':True},
            {'accountId':'b','rebuilt':-100,'projected':-100,'residual':0,'scannedEntries':1,'projectionPresent':True}]}}
        r.validate_rebuild(audit)
        audit['rebuild']['accounts'][0]['projected']=99
        with self.assertRaises(ValueError):r.validate_rebuild(audit)

class BudgetTests(unittest.TestCase):
    def test_full_budget_is_frozen(self):
        self.assertEqual({'history':1000000,'warmupSeconds':300,'measureSeconds':1800,'writeRps':100,'queryRps':200},r.budget('full'))
    def test_smoke_is_distinct_and_bounded(self):
        self.assertLess(r.budget('smoke')['history'],1000000)
        self.assertLess(r.budget('smoke')['measureSeconds'],1800)
    def test_unknown_profile_cannot_change_targets(self):
        with self.assertRaises(ValueError):r.budget('tiny-as-full')
    def test_performance_command_contains_exact_budget_and_output(self):
        command=r.performance_command('full',Path('/tmp/artifact'),'java','classes:deps')
        self.assertIn('com.paymentsledger.pr20.performance.Pr20PerformanceExperiment',command)
        self.assertIn('1800',command);self.assertIn('1000000',command);self.assertEqual('/tmp/artifact',command[command.index('--output')+1])
    def test_environment_removes_real_provider_credentials(self):
        env=r.test_environment({'STRIPE_API_KEY':'dummy','STRIPE_WEBHOOK_SECRET':'dummy','STRIPE_CONTRACT_TESTS':'true'})
        self.assertNotIn('STRIPE_API_KEY',env);self.assertNotIn('STRIPE_WEBHOOK_SECRET',env);self.assertEqual('false',env['STRIPE_CONTRACT_TESTS'])
    def test_percentiles_do_not_make_insufficient_evidence_pass(self):
        with self.assertRaises(ValueError):r.validate_performance({'status':'PASS','p95':1,'p99':1},'full')
    def test_smoke_cannot_be_presented_as_full(self):
        with self.assertRaises(ValueError):r.validate_performance({'status':'SMOKE_PASS','budget':r.budget('smoke')},'full')
    def test_backup_boot_only_cannot_release_funds(self):
        with self.assertRaises(ValueError):r.validate_recovery({'status':'PASS','databaseBooted':True})
    def test_reset_external_state_cannot_count_as_recovery(self):
        with self.assertRaises(ValueError):r.validate_recovery({'status':'PASS','externalStatePreserved':False,'writeGateReleased':True})
    def test_empty_demo_cannot_pass(self):
        with self.assertRaises(ValueError):r.validate_demo({'status':'PASS','cases':[]})
    def test_nonzero_command_failure_not_hidden_by_a_pass_report(self):
        self.assertEqual('FAIL',r.run_outcome(1,True));self.assertEqual('FAIL',r.run_outcome(0,False));self.assertEqual('PASS',r.run_outcome(0,True))


# Behavioral raw-file fixtures exercise report validation without Docker or real accounts.
import csv
import json
import tempfile
from datetime import datetime, timedelta, timezone

class RawEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.folder=Path(self.tmp.name)
        self.report=performance_report('smoke');self.start=datetime(2026,10,11,tzinfo=timezone.utc)
        self.report.update(measurementStartedAtUtc=self.start.isoformat(),measurementEndedAtUtc=(self.start+timedelta(seconds=20)).isoformat(),finalKafkaLag=0,drainCompleted=True)
        with (self.folder/'requests.csv').open('w',newline='') as f:
            writer=csv.DictWriter(f,fieldnames=['phase','kind','outcome','actualStartNanos','finishedNanos','latencyMs','status','resourceId']);writer.writeheader()
            for phase in ('warmup','measurement'):
                for kind,field in (('write','attemptedWrites'),('query','attemptedQueries')):
                    for i in range(self.report[phase][field]):writer.writerow(dict(phase=phase,kind=kind,outcome='OK',actualStartNanos=1000000000+i,finishedNanos=1010000000+i,latencyMs=10.,status=200,resourceId='fixture'))
                self.report[phase]['latencyMs']={k:{'p50':10.,'p95':10.,'p99':10.} for k in ('write','query')}
        resources='\n'.join(json.dumps({'Container':c,'CPUPerc':'10.0%','MemPerc':'20.0%','MemUsage':'100MiB / 1GiB','PIDs':'10','BlockIO':'1MB / 1MB','NetIO':'1MB / 1MB'}) for c in ('pg-fixture','kafka-fixture'))
        self.samples=[{'atUtc':(self.start+timedelta(seconds=n)).isoformat(),'db':{'numbackends':4,'xact_commit':1,'xact_rollback':0,'database_bytes':100},'provider':{'calls':1,'accepted':1,'capture_effects':1},'queues':copy.deepcopy(self.report['measurementEnd']['queues']),'kafkaLag':0,'containerResources':resources} for n in (0,5,10,15,20)]
        self.guards=[{'atUtc':self.start.isoformat(),'hostUsableBytes':10000000000,'hostTotalBytes':20000000000,'reserveBytes':2147483648,'dockerDataUsableBytes':10000000000,'dockerDataTotalBytes':20000000000}]
        self.jvm={a['name']:[{'at':self.start.isoformat(),'pid':a['pid'],'heapUsed':100,'heapMax':1000,'gcCount':0,'gcMillis':0,'processCpuNanos':1}] for a in self.report['actors']}
        self.write_raw()
    def write_raw(self):
        self.report['resourceSampleCount']=len(self.samples)
        for name,rows in (('samples.ndjson',self.samples),('disk-guards.ndjson',self.guards)):(self.folder/name).write_text(''.join(json.dumps(row)+'\n' for row in rows))
        for actor,ready in zip(self.report['actors'],self.report['roleReadiness']):
            directory=self.folder/actor['name'];directory.mkdir(exist_ok=True)
            (directory/'ready.json').write_text(json.dumps(ready));(directory/'jvm.ndjson').write_text(''.join(json.dumps(row)+'\n' for row in self.jvm[actor['name']]))
    def reject(self):
        self.write_raw()
        with self.assertRaises(ValueError):r.validate_performance(self.report,'smoke',self.folder)
    def test_complete_raw_files_are_accepted(self):r.validate_performance(self.report,'smoke',self.folder)
    def test_null_database_metrics_rejected(self):self.samples[0]['db']=None;self.reject()
    def test_null_provider_metrics_rejected(self):self.samples[0]['provider']=None;self.reject()
    def test_null_queue_metrics_rejected(self):self.samples[0]['queues']=None;self.reject()
    def test_missing_database_counters_rejected(self):self.samples[0]['db']={};self.reject()
    def test_null_container_resources_rejected(self):self.samples[0]['containerResources']=None;self.reject()
    def test_unrelated_container_resources_rejected(self):self.samples[0]['containerResources']=self.samples[0]['containerResources'].replace('pg-fixture','unrelated');self.reject()
    def test_nan_container_cpu_rejected(self):self.samples[0]['containerResources']=self.samples[0]['containerResources'].replace('10.0%','NaN%');self.reject()
    def test_null_jvm_metrics_rejected(self):self.jvm['api-1'][0]['heapUsed']=None;self.reject()
    def test_empty_disk_guard_rejected(self):self.guards=[{}];self.reject()
    def test_stale_resource_samples_rejected(self):self.samples=self.samples[:1];self.samples[0]['atUtc']=(self.start-timedelta(hours=1)).isoformat();self.reject()
    def test_no_drained_kafka_cannot_pass(self):self.report['finalKafkaLag']=1;self.reject()
    def test_unfinished_drain_cannot_pass(self):self.report['drainCompleted']=False;self.reject()

class SourceBindingTests(unittest.TestCase):
    def test_reported_source_hashes_bind_to_frozen_inputs(self):r.validate_source_binding({'src/main/a.java':'b'*64},{'src/main/a.java':'b'*64})
    def test_wrong_source_hash_rejected(self):
        with self.assertRaises(ValueError):r.validate_source_binding({'src/main/a.java':'c'*64},{'src/main/a.java':'b'*64})
    def test_unknown_source_path_rejected(self):
        with self.assertRaises(ValueError):r.validate_source_binding({'src/main/b.java':'b'*64},{'src/main/a.java':'b'*64})
    def test_empty_source_hashes_rejected(self):
        with self.assertRaises(ValueError):r.validate_source_binding({}, {'src/main/a.java':'b'*64})

if __name__=='__main__':unittest.main()
