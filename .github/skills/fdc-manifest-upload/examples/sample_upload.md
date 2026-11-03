# Example conversations

Values are made up. `<skill>` is the skill folder.

## 1. Happy path (SSO already active)
**User:** Upload my manifest to FDC.
**Agent:** Sure. Which FDC environment?
**User:** UAT
**Agent:** Where is your casc.properties file?
**User:** /workspace/my-project/casc.properties
**Agent:** Please give or confirm Server, Meta, Project, Release and Distribution System.
**User:** Server fdc-uat.example.org, meta metaA, project MyProject, release 1.0, distribution system distA.
**Agent:** *(runs `python3 <skill>/scripts/validate_upload_prerequisites.py --environment UAT --properties /workspace/my-project/casc.properties --server fdc-uat.example.org --meta metaA --project MyProject --release 1.0 --distribution-system distA`)*
All mandatory checks passed. Checking SSO... *(runs `auth_check.py --method sso --environment UAT --project MyProject --release 1.0`)* Your SSO session is active and you are authorized for UAT.
*(runs `run_upload.py plan ... --auth-method sso` and shows:)*
```
UPLOAD SUMMARY
  Target FDC environment : UAT
  Server (-s)            : fdc-uat.example.org
  Meta (-m)              : metaA
  Project (-p)           : MyProject
  Release (-r)           : 1.0
  Distribution system(-md): distA
  casc.properties        : /workspace/my-project/casc.properties
  Project root           : /workspace/my-project
  Runs in folder         : /workspace/my-project
  Authentication         : SSO
  Update behaviour       : decided by the existing script from casc.properties; the skill does not interpret it
  Command                : /ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py -s fdc-uat.example.org -m metaA -p MyProject -r 1.0 -e UAT -md distA
  WARNING: depending on casc.properties, this upload may DELETE existing FDC manifest entries that are not in the incoming manifest.
  Plan hash: 3f9a1c27d0b4e612
```
**Agent:** This upload may delete existing FDC manifest entries that are not in the incoming manifest, depending on your casc.properties. Do you want to proceed?
**User:** Yes.
**Agent:** *(runs `run_upload.py execute ... --plan-hash 3f9a1c27d0b4e612 --confirmed yes`)*
```
RESULT: UPLOAD SUCCEEDED
  Environment : UAT
  Project     : MyProject   Release: 1.0
  Auth method : SSO
```
Manifest uploaded successfully to UAT.

## 2. SSO session missing
**Agent:** *(auth check exits 10)* You have no active SSO session. Please sign in in your own terminal with: `<sso login command for UAT>` (in Claude Code you can type `!` first). Tell me when you are done.
**User:** Done.
**Agent:** *(runs the check again)* Authentication and authorization verified for UAT. Here is the upload summary...

## 3. SSO cannot be completed, PROID fallback
**Agent:** SSO could not be completed. If your organisation allows it, I can use a PROID. What is the PROID name? (Never type a password here.)
**User:** svc_fdc_app
**Agent:** *(runs `auth_check.py --method proid --environment UAT --proid svc_fdc_app ...`)* The PROID was authenticated through the approved command and is authorized for UAT. *(plan uses `--auth-method proid --proid svc_fdc_app`)*

## 4. Not authorized
**Agent:** You are signed in, but you are not authorized to upload to PROD (exit 13). I will not continue or try another environment. Please request access for PROD.

## 5. Access checks not configured
**Agent:** The access checks are not configured on this machine (FDC_SSO_STATUS_CMD and FDC_AUTHZ_CHECK_CMD are unset), so I cannot verify your authentication or authorization. The upload script enforces access itself. Do you want to continue anyway, knowing the summary will say "NOT VERIFIED by the skill"?
**User:** Yes, continue.
*(plan and execute use `--auth-method unverified`, and execute adds `--ack-unverified-auth`)*

## 6. Validation problem
**Agent:** *(validation prints `[FAIL] casc.properties: No file at /workspace/my-project/casc.properties`)* I could not find a file at that path. Can you check the location?

## 7. Failure
```
RESULT: UPLOAD FAILED
  Exit code   : 1   (exit code 1)
  Likely cause (heuristic, from the output): Authorization
  Troubleshooting: Your identity is not allowed to upload to this environment. Ask the FDC administrators for access; do not try another environment.
```
**Agent:** The upload failed. The output suggests an authorization problem (403 Forbidden). Nothing was reported as uploaded. Please ask the FDC administrators for access to UAT.

## 8. Exit code 0, but nothing proves it worked
```
RESULT: UPLOAD NOT VERIFIED (do not treat it as successful yet)
  Exit code   : 0   (exit code 0, but no success pattern is configured, so the upload cannot be independently verified)
```
**Agent:** The script finished without an error, but I cannot independently verify that the manifest arrived. Please check FDC, or give me the script's success message so it can be configured.
