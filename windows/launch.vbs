' Starts Bambu Widget detached from any console window.
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
appDir = sh.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\BambuWidget"
exe = appDir & "\node_modules\electron\dist\electron.exe"
If Not fso.FileExists(exe) Then
  MsgBox "Bambu Widget is not installed yet. Run install.bat first.", 48, "Bambu Widget"
  WScript.Quit 1
End If
sh.Environment("Process")("ELECTRON_NO_ATTACH_CONSOLE") = "1"
sh.Run """" & exe & """ """ & appDir & """", 1, False
