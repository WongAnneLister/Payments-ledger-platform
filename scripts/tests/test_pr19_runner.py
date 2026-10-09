import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'ci'))
import run_pr19 as runner
class RunnerTests(unittest.TestCase):
 def artifact(self,folder,fault,variant):
  data={'id':fault,'variant':variant,'testId':fault+'/'+variant,'status':'PASS','seed':19,'injection':'controlled','recovery':'production','limitations':'Fake','providerEvidence':'FAKE_ONLY','bankEvidence':'SIMULATED_BANK_EVIDENCE','startedAtUtc':'2026-10-09T00:00:00Z','completedAtUtc':'2026-10-09T00:00:01Z','elapsedMillis':1000,'recoveryMillis':4,'dataset':{'amount':1000},'operationKeys':['fixed-key'],'observations':{'actual':1},'intermediateEvidence':{'actual':0},'finalEvidence':{'actual':1},'counts':{k:0 for k in runner.COUNTS}}
  data.update(title='fixture',expected='fixture expected',sourceCommit='a'*40,sourceProvenanceReport='run-summary.json',replayCommand='python3 scripts/ci/run_pr19.py --fault '+fault,environment={'javaVersion':'21','os':'fixture','architecture':'fixture'},dataVolume={'payment':1})
  if fault in ('F-06','F-08'):
   data['processes']=[{'javaPid':101,'ready':{'backendPid':201},'forceKilled':True,'exitCode':137},{'javaPid':102,'ready':{'backendPid':202},'forceKilled':False,'exitCode':0}]
   if fault=='F-08':data['observations']['executionMode']='SEPARATE_JVMS_AND_POSTGRESQL_CONNECTIONS'
   if variant=='lease-fencing-controlled':data['observations']['controlledSample']=True
  (folder/f'{fault}-{variant}.json').write_text(json.dumps(data));return data
 def complete(self,folder,fault):
  for v in runner.VARIANTS[fault]:self.artifact(folder,fault,v)
 def test_explicit_commands_and_fake_environment(self):
  self.assertIn('clean',runner.build_command('all'));self.assertIn('-Dpr18.seeds=100',runner.build_command('all'))
  self.assertIn('-Dit.test=Pr19ProviderFaultIT',runner.build_command('F-05'))
  env=runner.test_environment({'STRIPE_API_KEY':'dummy','PR06_STRIPE_CONTRACT':'true'});self.assertNotIn('STRIPE_API_KEY',env);self.assertEqual('false',env['PR06_STRIPE_CONTRACT'])
 def test_invalid_fault_rejected(self):
  with self.assertRaises(ValueError):runner.build_command('F-09')
 def test_complete_actual_variants_accepted(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.complete(p,'F-05');report=runner.validate_faults(p,'F-05');self.assertEqual(len(runner.VARIANTS['F-05']),len(report))
 def test_missing_variant_fails(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.complete(p,'F-05');(p/f"F-05-{runner.VARIANTS['F-05'][0]}.json").unlink()
   with self.assertRaises(ValueError):runner.validate_faults(p,'F-05')
 def test_failed_malformed_or_mismatched_artifacts_fail(self):
  for field,value in [('status','FAIL'),('id','F-08'),('variant','wrong'),('recoveryMillis',-1),('dataset',{}),('counts',{'requests':False})]:
   with self.subTest(field=field),tempfile.TemporaryDirectory() as d:
    p=Path(d);self.complete(p,'F-05');v=runner.VARIANTS['F-05'][0];data=self.artifact(p,'F-05',v);data[field]=value;(p/f'F-05-{v}.json').write_text(json.dumps(data))
    with self.assertRaises(ValueError):runner.validate_faults(p,'F-05')
 def test_malformed_json_fails(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.complete(p,'F-05');(p/f"F-05-{runner.VARIANTS['F-05'][0]}.json").write_text('null')
   with self.assertRaises(ValueError):runner.validate_faults(p,'F-05')
 def test_suite_skips_and_missing_tests_fail(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'TEST-Pr19ProviderFaultIT.xml').write_text('<testsuite name="x.Pr19ProviderFaultIT" tests="1" failures="0" errors="0" skipped="1"/>')
   with self.assertRaises(ValueError):runner.validate_suites(p,'F-05')
 def test_command_failure_never_becomes_pass(self):
  self.assertEqual('FAIL',runner.outcome(7,False));self.assertEqual('FAIL',runner.outcome(0,False));self.assertEqual('PASS',runner.outcome(0,True))
 def test_aggregate_missing_jobs_is_failure(self):
  with tempfile.TemporaryDirectory() as d:
   folder=Path(d);self.assertEqual(1,runner.aggregate(folder,folder/'summary.json'));self.assertEqual('FAIL',json.loads((folder/'summary.json').read_text())['status'])
 def test_aggregate_revalidates_all_artifacts_and_source_identity(self):
  with tempfile.TemporaryDirectory() as d:
   folder=Path(d)
   for fault in runner.VARIANTS:
    p=folder/fault/'pr19';p.mkdir(parents=True);self.complete(p,fault)
    reports=p.parent/'failsafe-reports';reports.mkdir()
    expected={'Pr19WebhookFaultIT':17,'Pr19ProviderFaultIT':6,'Pr19ProcessFaultIT':7,'Pr19KafkaFaultIT':5,'Pr19MultiProcessIT':7}
    for c in runner.CLASSES[fault]:
     full='com.paymentsledger.'+('notification.' if c=='Pr19KafkaFaultIT' else 'payment.')+c
     cases=''.join(f'<testcase name="fixture-{i}" classname="{full}"/>' for i in range(expected[c]))
     (reports/f'TEST-{c}.xml').write_text(f'<testsuite name="{full}" tests="{expected[c]}" failures="0" errors="0" skipped="0">{cases}</testsuite>')
    (p/'run-summary.json').write_text(json.dumps({'fault':fault,'status':'PASS','mavenExitCode':0,'inputDrift':False,'commit':{'output':'a'*40},'inputFilesSha256':{'a':'b'*64}}))
   output=folder/'summary.json';self.assertEqual(0,runner.aggregate(folder,output));self.assertEqual(42,json.loads(output.read_text())['variantCount'])
   file=folder/'F-08/pr19/run-summary.json';data=json.loads(file.read_text());data['commit']['output']='different';file.write_text(json.dumps(data));self.assertEqual(1,runner.aggregate(folder,output))
 def test_single_process_cannot_pass_concurrency_matrix(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.complete(p,'F-08');v=runner.VARIANTS['F-08'][0];data=self.artifact(p,'F-08',v);data['processes'][1]['javaPid']=101;(p/f'F-08-{v}.json').write_text(json.dumps(data))
   with self.assertRaises(ValueError):runner.validate_faults(p,'F-08')
 def test_missing_environment_capability_is_explicitly_blocked(self):
  self.assertEqual(['docker'],runner.capability_problem({'java':{'exitCode':0},'docker':{'status':'UNAVAILABLE'}}))
  self.assertEqual([],runner.capability_problem({'java':{'exitCode':0},'docker':{'exitCode':0}}))
 def test_one_case_does_not_cover_six_case_provider_suite(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'TEST-Pr19ProviderFaultIT.xml').write_text('<testsuite name="com.paymentsledger.payment.Pr19ProviderFaultIT" tests="1" failures="0" errors="0" skipped="0"><testcase name="only-one" classname="com.paymentsledger.payment.Pr19ProviderFaultIT"/></testsuite>')
   with self.assertRaises(ValueError):runner.validate_suites(p,'F-05')
 def test_absent_junit_outcome_counters_and_testcases_fail(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'TEST-Pr19ProviderFaultIT.xml').write_text('<testsuite name="com.paymentsledger.payment.Pr19ProviderFaultIT" tests="1"/>')
   with self.assertRaises(ValueError):runner.validate_suites(p,'F-05')
 def test_experiment_missing_provenance_cannot_pass(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.complete(p,'F-05');v=runner.VARIANTS['F-05'][0];data=self.artifact(p,'F-05',v);data['sourceCommit']='';(p/f'F-05-{v}.json').write_text(json.dumps(data))
   with self.assertRaises(ValueError):runner.validate_faults(p,'F-05')
 def test_nonstructured_evidence_is_rejected(self):
  for field in ('dataset','observations','intermediateEvidence','finalEvidence'):
   with self.subTest(field=field),tempfile.TemporaryDirectory() as d:
    p=Path(d);self.complete(p,'F-05');v=runner.VARIANTS['F-05'][0];data=self.artifact(p,'F-05',v);data[field]='not structured';(p/f'F-05-{v}.json').write_text(json.dumps(data))
    with self.assertRaises(ValueError):runner.validate_faults(p,'F-05')
 def test_null_nested_process_marker_is_reported_validation_failure(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.complete(p,'F-08');v=runner.VARIANTS['F-08'][0];data=self.artifact(p,'F-08',v);data['processes'][0]['ready']=None;(p/f'F-08-{v}.json').write_text(json.dumps(data))
   with self.assertRaises(ValueError):runner.validate_faults(p,'F-08')
 def test_aggregate_malformed_commit_still_writes_fail_report(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run-summary.json').write_text(json.dumps({'fault':'F-05','status':'PASS','mavenExitCode':0,'inputDrift':False,'commit':None,'inputFilesSha256':{'a':'b'*64}}));output=p/'matrix.json'
   self.assertEqual(1,runner.aggregate(p,output));self.assertEqual('FAIL',json.loads(output.read_text())['status'])
if __name__=='__main__':unittest.main()
