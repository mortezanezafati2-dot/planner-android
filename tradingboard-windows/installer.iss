#define MyAppName "Backtest Lab"
#define MyAppVersion "2.0.0"
#define MyAppExeName "BacktestLab.exe"
[Setup]
AppId={{6A5D5E40-1C7A-4B5B-9C8A-4F7A2B5C3D11}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
DefaultDirName={autopf}\\Backtest Lab
DefaultGroupName={#MyAppName}
OutputDir=dist
OutputBaseFilename=BacktestLab-Setup
Compression=lzma
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64
WizardStyle=modern
[Files]
Source: "dist\\BacktestLab.exe"; DestDir: "{app}"; Flags: ignoreversion
[Icons]
Name: "{group}\\{#MyAppName}"; Filename: "{app}\\{#MyAppExeName}"
Name: "{autodesktop}\\{#MyAppName}"; Filename: "{app}\\{#MyAppExeName}"
[Run]
Filename: "{app}\\{#MyAppExeName}"; Description: "اجرای {#MyAppName}"; Flags: nowait postinstall skipifsilent
