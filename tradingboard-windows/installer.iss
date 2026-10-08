#define MyAppName "Backtest Lab"
#define MyAppVersion "2.0.0"
#define MyAppPublisher "Backtest Lab"
#define MyAppExeName "BacktestLab.exe"

[Setup]
AppId={{F63A20BF-4C67-4CE0-B0E0-BACKTESTLAB02}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\Backtest Lab
DefaultGroupName=Backtest Lab
OutputDir=dist-installer
OutputBaseFilename=BacktestLab-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
UninstallDisplayIcon={app}\{#MyAppExeName}

[Files]
Source: "dist\BacktestLab\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Backtest Lab"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\Backtest Lab"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "ایجاد میانبر روی دسکتاپ"; GroupDescription: "میانبرهای اضافی:"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "اجرای Backtest Lab"; Flags: nowait postinstall skipifsilent
