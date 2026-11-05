; Inno Setup script for MediaPlayer (Windows x64). Built by CI:
;   iscc /DAppVersion=1.2.0 windows\installer.iss
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{6F1B6A0C-3E5B-4C55-9F0E-8E6D2B7C4A11}
AppName=MediaPlayer
AppVersion={#AppVersion}
AppPublisher=Arthuralend
AppPublisherURL=https://github.com/Arthuralend/TorVideoPlayer
DefaultDirName={autopf}\MediaPlayer
DefaultGroupName=MediaPlayer
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\build
OutputBaseFilename=MediaPlayer-Setup-x64
SetupIconFile=MediaPlayer\app.ico
UninstallDisplayIcon={app}\MediaPlayer.exe
WizardStyle=modern
Compression=lzma2/max
SolidCompression=yes
CloseApplications=yes

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "associate"; Description: "Открывать magnet-ссылки и .torrent файлы в MediaPlayer"; GroupDescription: "Ассоциации:"

[Files]
Source: "..\build\windows\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\MediaPlayer"; Filename: "{app}\MediaPlayer.exe"
Name: "{autodesktop}\MediaPlayer"; Filename: "{app}\MediaPlayer.exe"; Tasks: desktopicon

[Registry]
Root: HKA; Subkey: "Software\Classes\magnet"; ValueType: string; ValueName: ""; ValueData: "URL:Magnet link"; Tasks: associate; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\magnet"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""; Tasks: associate
Root: HKA; Subkey: "Software\Classes\magnet\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\MediaPlayer.exe"",0"; Tasks: associate
Root: HKA; Subkey: "Software\Classes\magnet\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\MediaPlayer.exe"" ""%1"""; Tasks: associate
Root: HKA; Subkey: "Software\Classes\MediaPlayer.Torrent"; ValueType: string; ValueName: ""; ValueData: "Торрент-файл"; Tasks: associate; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\MediaPlayer.Torrent\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\MediaPlayer.exe"",0"; Tasks: associate
Root: HKA; Subkey: "Software\Classes\MediaPlayer.Torrent\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\MediaPlayer.exe"" ""%1"""; Tasks: associate
Root: HKA; Subkey: "Software\Classes\.torrent"; ValueType: string; ValueName: ""; ValueData: "MediaPlayer.Torrent"; Tasks: associate
Root: HKA; Subkey: "Software\Classes\.torrent\OpenWithProgids"; ValueType: none; ValueName: "MediaPlayer.Torrent"; Tasks: associate; Flags: uninsdeletevalue

[Run]
Filename: "{app}\MediaPlayer.exe"; Description: "{cm:LaunchProgram,MediaPlayer}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "taskkill.exe"; Parameters: "/F /IM torengine.exe"; Flags: runhidden; RunOnceId: "KillEngine"
