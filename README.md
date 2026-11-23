# Chérie Read

Chérie Read is a standalone Android reading app. It is not 蔷薇书笺.

## Bootstrap v1.3.3 and build APK

The repository has a bootstrap workflow ready. Upload this one archive to the repository root:

`CherieRead-v1.3.3-GitHub-APK-Project.zip`

Then GitHub Actions will:
1. Expand the ZIP into the repository root.
2. Remove the bootstrap archive and temporary bootstrap workflow.
3. Commit the full Android source tree.
4. Build a Debug APK with JDK 17, Android SDK 34, and Gradle 8.7.
5. Publish the APK as a 30-day Actions artifact.

On mobile browser: open the repository, choose **Add file → Upload files**, select the ZIP, and commit to `main`. Then open **Actions** and check the run named **Bootstrap and Build Chérie Read APK**. After success, download the artifact named `CherieRead-Android-APK-v1.3.3`.

The full project will then be in the repository and future commits will use the normal `Build Chérie Read Android APK` workflow.
