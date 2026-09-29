// Shark Hub Installer: a one-window Windows app that puts Shark Hub (or any APK) on a BYD head unit
// over Wi-Fi ADB. Built with the .NET Framework's own C# compiler (C# 5, so no string
// interpolation or ?.) into one portable exe; see build.ps1. The window is Installer.xaml, embedded
// and loaded at runtime, so everything visual lives there and this file wires it by element name.
//
// The ADB work is done by Google's adb.exe: the one next to this exe, the Android SDK's, one on PATH,
// or platform-tools downloaded from Google into %LOCALAPPDATA%\SharkHubInstaller on first use.

using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using Microsoft.Win32;

[assembly: AssemblyTitle("Shark Hub Installer")]
[assembly: AssemblyProduct("Shark Hub")]
[assembly: AssemblyDescription("Installs Shark Hub on a BYD Shark 6 head unit over Wi-Fi ADB")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace SharkHub.Installer
{
    public static class Program
    {
        [STAThread]
        public static int Main(string[] args)
        {
            var app = new Application();
            app.ShutdownMode = ShutdownMode.OnMainWindowClose;
            try
            {
                var ui = new InstallerWindow();
                // "--snapshot out.png [idle|ready|allow|busy|done] [some.apk]" renders the window to a
                // PNG and exits: how the README pictures and the build check are made.
                if (args.Length >= 2 && args[0] == "--snapshot")
                    return ui.Snapshot(args[1], args.Length > 2 ? args[2] : "idle", args.Length > 3 ? args[3] : null);
                // developer checks for the scanner, written to a file (a windowed exe has no console):
                // "--probe <ip> <out.txt>" handshakes one address; "--candidates <out.txt>" lists what a scan covers
                if (args.Length >= 3 && args[0] == "--probe")
                {
                    var ip = IPAddress.Parse(args[1]);
                    int kind = Task.Run(() => Finder.ProbeAsync(ip)).Result;
                    File.WriteAllText(args[2], kind == 2 ? "adb" : kind == 1 ? "open" : "none");
                    return 0;
                }
                if (args.Length >= 2 && args[0] == "--candidates")
                {
                    File.WriteAllLines(args[1], Finder.Describe().ToArray());
                    return 0;
                }
                // an APK dropped on the exe (or passed by "Open with") comes in as the first argument
                if (args.Length >= 1) ui.Preselect(args[0]);
                return app.Run(ui.Window);
            }
            catch (Exception e)
            {
                MessageBox.Show("Shark Hub Installer couldn't start:\n\n" + e, "Shark Hub Installer", MessageBoxButton.OK, MessageBoxImage.Error);
                return 1;
            }
        }
    }

    /// <summary>Where the installer keeps things, and the few settings it remembers.</summary>
    static class Store
    {
        public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SharkHubInstaller");
        public static readonly string AppDir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);

        /// <summary>
        /// The GitHub repository whose latest release carries the Shark Hub APK, as "owner/name".
        /// A line "repo=owner/name" in SharkHubInstaller.txt next to the exe (or in the settings file)
        /// overrides it without a rebuild.
        /// </summary>
        public const string DefaultRepo = "Peacemaker105/SharkHub";

        static Dictionary<string, string> Read(string path)
        {
            var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            try
            {
                if (!File.Exists(path)) return d;
                foreach (var line in File.ReadAllLines(path))
                {
                    int eq = line.IndexOf('=');
                    if (eq > 0) d[line.Substring(0, eq).Trim()] = line.Substring(eq + 1).Trim();
                }
            }
            catch { }
            return d;
        }

        static string SettingsPath { get { return Path.Combine(Dir, "settings.txt"); } }

        public static string Get(string key)
        {
            string v;
            if (Read(Path.Combine(AppDir, "SharkHubInstaller.txt")).TryGetValue(key, out v) && v.Length > 0) return v;
            if (Read(SettingsPath).TryGetValue(key, out v) && v.Length > 0) return v;
            return null;
        }

        public static void Set(string key, string value)
        {
            try
            {
                Directory.CreateDirectory(Dir);
                var d = Read(SettingsPath);
                d[key] = value;
                File.WriteAllLines(SettingsPath, d.Select(kv => kv.Key + "=" + kv.Value).ToArray());
            }
            catch { }
        }

        public static string Repo { get { return Get("repo") ?? DefaultRepo; } }
    }

    class RunResult
    {
        public int Code;
        public string Output;
        public RunResult(int code, string output) { Code = code; Output = output; }
    }

    /// <summary>Finding (or fetching) adb.exe and running it.</summary>
    static class Adb
    {
        const string PlatformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip";

        public static string Find()
        {
            var candidates = new List<string>();
            candidates.Add(Path.Combine(Store.AppDir, "adb.exe"));
            candidates.Add(Path.Combine(Store.AppDir, "platform-tools", "adb.exe"));
            candidates.Add(Path.Combine(Store.Dir, "platform-tools", "adb.exe"));
            foreach (var env in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" })
            {
                var root = Environment.GetEnvironmentVariable(env);
                if (!string.IsNullOrEmpty(root)) candidates.Add(Path.Combine(root, "platform-tools", "adb.exe"));
            }
            candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb.exe"));
            var path = Environment.GetEnvironmentVariable("PATH") ?? "";
            foreach (var dir in path.Split(';'))
            {
                if (dir.Trim().Length == 0) continue;
                try { candidates.Add(Path.Combine(dir.Trim(), "adb.exe")); } catch { }
            }
            return candidates.FirstOrDefault(File.Exists);
        }

        /// <summary>Google's platform-tools zip into our folder; returns the adb.exe inside it.</summary>
        public static async Task<string> DownloadAsync(Action<double> progress)
        {
            Directory.CreateDirectory(Store.Dir);
            var zip = Path.Combine(Store.Dir, "platform-tools.zip");
            using (var wc = new WebClient())
            {
                wc.DownloadProgressChanged += (s, e) => { if (e.TotalBytesToReceive > 0) progress(e.BytesReceived / (double)e.TotalBytesToReceive); };
                await wc.DownloadFileTaskAsync(new Uri(PlatformToolsUrl), zip);
            }
            var target = Path.Combine(Store.Dir, "platform-tools");
            await Task.Run(() =>
            {
                if (Directory.Exists(target)) Directory.Delete(target, true);
                ZipFile.ExtractToDirectory(zip, Store.Dir);
                File.Delete(zip);
            });
            var adb = Path.Combine(target, "adb.exe");
            if (!File.Exists(adb)) throw new IOException("adb.exe wasn't in Google's platform-tools download.");
            return adb;
        }

        /// <summary>
        /// Start the adb server with no pipes attached. Started from a redirected call instead, the
        /// long-lived server can inherit our output pipes and every later read would wait on it.
        /// </summary>
        public static Task StartServerAsync(string adb)
        {
            return Task.Run(() =>
            {
                var psi = new ProcessStartInfo(adb, "start-server");
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                using (var p = Process.Start(psi)) { p.WaitForExit(30000); }
            });
        }

        public static Task<RunResult> RunAsync(string adb, string args, int timeoutMs)
        {
            return Task.Run(() =>
            {
                var psi = new ProcessStartInfo(adb, args);
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                psi.StandardOutputEncoding = Encoding.UTF8;
                psi.StandardErrorEncoding = Encoding.UTF8;
                var sb = new StringBuilder();
                var gate = new object();
                var outDone = new ManualResetEvent(false);
                var errDone = new ManualResetEvent(false);
                using (var p = new Process())
                {
                    p.StartInfo = psi;
                    p.OutputDataReceived += (s, e) => { if (e.Data == null) outDone.Set(); else lock (gate) sb.AppendLine(e.Data); };
                    p.ErrorDataReceived += (s, e) => { if (e.Data == null) errDone.Set(); else lock (gate) sb.AppendLine(e.Data); };
                    p.Start();
                    p.BeginOutputReadLine();
                    p.BeginErrorReadLine();
                    if (!p.WaitForExit(timeoutMs))
                    {
                        try { p.Kill(); } catch { }
                        lock (gate) return new RunResult(-1, sb.ToString() + "(timed out)");
                    }
                    WaitHandle.WaitAll(new WaitHandle[] { outDone, errDone }, 3000);
                    lock (gate) return new RunResult(p.ExitCode, sb.ToString().Trim());
                }
            });
        }
    }

    /// <summary>
    /// Finds the car for people who don't know its IP: every address on this PC's own networks is
    /// tried on ADB's port 5555, and anything that answers gets ADB's opening message (CNXN). A car
    /// with ADB on replies AUTH (or CNXN / STLS) straight away. Nothing is sent that would make the
    /// car ask "Allow USB debugging?" — that only happens when a key is offered, which a scan never does.
    /// </summary>
    static class Finder
    {
        const uint CNXN = 0x4e584e43, AUTH = 0x48545541, STLS = 0x534c5453;
        const int Port = 5555;

        public class Hit
        {
            public string Ip;
            /// <summary>True when it answered like adbd; false when the port was merely open.</summary>
            public bool Adb;
        }

        class Net
        {
            public string Name;
            public uint Own, First, Last;
        }

        // Virtual adapters (Hyper-V, WSL, VPNs, VMs) can't have the car on them; skipping them keeps a scan quick.
        static readonly string[] Skip = { "hyper-v", "vethernet", "virtualbox", "vmware", "wsl", "loopback", "bluetooth", "tap-", "wireguard", "zerotier", "tailscale" };

        static List<Net> Networks()
        {
            var nets = new List<Net>();
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback || ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel) continue;
                var label = (ni.Name + " " + ni.Description).ToLowerInvariant();
                if (Skip.Any(label.Contains)) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                    uint ip = ToUint(ua.Address), mask = ToUint(ua.IPv4Mask);
                    if ((ip >> 16) == 0xA9FE) continue;            // 169.254.x.x: no DHCP here, so no car either
                    if (mask < 0xFFFFFF00) mask = 0xFFFFFF00;      // a wide network is cut to the 254 addresses around us
                    uint net = ip & mask, bcast = net | ~mask;
                    if (bcast - net < 2) continue;
                    var n = new Net();
                    n.Name = ni.Name; n.Own = ip; n.First = net + 1; n.Last = bcast - 1;
                    nets.Add(n);
                }
            }
            return nets;
        }

        public static List<string> Describe()
        {
            return Networks().Select(n => string.Format("{0}: {1} - {2} (this PC {3})", n.Name, FromUint(n.First), FromUint(n.Last), FromUint(n.Own))).ToList();
        }

        public static async Task<List<Hit>> ScanAsync(Action<int, int> progress)
        {
            var hosts = new List<IPAddress>();
            var seen = new HashSet<uint>();
            foreach (var n in Networks())
                for (uint a = n.First; a <= n.Last; a++)
                    if (a != n.Own && seen.Add(a)) hosts.Add(FromUint(a));
            var hits = new List<Hit>();
            var gate = new object();
            int done = 0;
            var throttle = new SemaphoreSlim(64);
            var tasks = hosts.Select(async ip =>
            {
                await throttle.WaitAsync();
                try
                {
                    int kind = await ProbeAsync(ip);
                    if (kind > 0) lock (gate) { var h = new Hit(); h.Ip = ip.ToString(); h.Adb = kind == 2; hits.Add(h); }
                }
                finally
                {
                    throttle.Release();
                    progress(Interlocked.Increment(ref done), hosts.Count);
                }
            }).ToList();
            await Task.WhenAll(tasks);
            return hits.OrderByDescending(h => h.Adb).ThenBy(h => ToUint(IPAddress.Parse(h.Ip))).ToList();
        }

        /// <summary>0 = nothing there, 1 = port 5555 open but not answering like ADB, 2 = ADB.</summary>
        public static async Task<int> ProbeAsync(IPAddress ip)
        {
            var client = new TcpClient();
            try
            {
                var connect = client.ConnectAsync(ip, Port);
                if (await Task.WhenAny(connect, Task.Delay(700)) != connect || connect.IsFaulted || !client.Connected)
                {
                    Observe(connect);
                    return 0;
                }
                var stream = client.GetStream();
                var hello = Packet(CNXN, 0x01000000, 4096, Encoding.ASCII.GetBytes("host::\0"));
                await stream.WriteAsync(hello, 0, hello.Length);
                var header = new byte[24];
                var read = stream.ReadAsync(header, 0, header.Length);
                if (await Task.WhenAny(read, Task.Delay(1000)) != read || read.IsFaulted)
                {
                    Observe(read);
                    return 1;
                }
                if (read.Result < 4) return 1;
                uint cmd = BitConverter.ToUInt32(header, 0);
                return cmd == AUTH || cmd == CNXN || cmd == STLS ? 2 : 1;
            }
            catch { return 0; }
            finally { client.Close(); }
        }

        static void Observe(Task t) { t.ContinueWith(x => { var ignored = x.Exception; }, TaskContinuationOptions.OnlyOnFaulted); }

        /// <summary>An ADB message: 24-byte little-endian header (command, two args, length, byte sum, magic), then data.</summary>
        static byte[] Packet(uint cmd, uint arg0, uint arg1, byte[] data)
        {
            var p = new byte[24 + data.Length];
            uint sum = 0;
            foreach (var b in data) sum += b;
            Put(p, 0, cmd); Put(p, 4, arg0); Put(p, 8, arg1); Put(p, 12, (uint)data.Length); Put(p, 16, sum); Put(p, 20, cmd ^ 0xFFFFFFFF);
            Buffer.BlockCopy(data, 0, p, 24, data.Length);
            return p;
        }

        static void Put(byte[] p, int at, uint v) { p[at] = (byte)v; p[at + 1] = (byte)(v >> 8); p[at + 2] = (byte)(v >> 16); p[at + 3] = (byte)(v >> 24); }

        static uint ToUint(IPAddress a)
        {
            var b = a.GetAddressBytes();
            return ((uint)b[0] << 24) | ((uint)b[1] << 16) | ((uint)b[2] << 8) | b[3];
        }

        static IPAddress FromUint(uint v) { return new IPAddress(new[] { (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v }); }
    }

    class Release
    {
        public string Tag;
        public string AssetName;
        public string Url;
        public long Size;
    }

    static class GitHub
    {
        public static async Task<Release> LatestAsync(string repo)
        {
            using (var wc = new WebClient())
            {
                wc.Headers[HttpRequestHeader.UserAgent] = "SharkHubInstaller";
                wc.Headers[HttpRequestHeader.Accept] = "application/vnd.github+json";
                string json;
                try
                {
                    json = await wc.DownloadStringTaskAsync(new Uri("https://api.github.com/repos/" + repo + "/releases/latest"));
                }
                catch (WebException e)
                {
                    var r = e.Response as HttpWebResponse;
                    if (r != null && r.StatusCode == HttpStatusCode.NotFound)
                        throw new InvalidOperationException("No Shark Hub release has been published at github.com/" + repo + " yet.");
                    throw;
                }
                var d = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(json);
                var rel = new Release();
                object tag;
                rel.Tag = d.TryGetValue("tag_name", out tag) ? tag as string : null;
                object assets;
                if (d.TryGetValue("assets", out assets) && assets is IEnumerable)
                {
                    // A release also carries the phone installer's APK: take Shark Hub's own, by name,
                    // and only fall back to the first APK when nothing is named for it.
                    Dictionary<string, object> pick = null, first = null;
                    foreach (var a in (IEnumerable)assets)
                    {
                        var ad = a as Dictionary<string, object>;
                        if (ad == null) continue;
                        var name = ad["name"] as string;
                        if (name == null || !name.EndsWith(".apk", StringComparison.OrdinalIgnoreCase)) continue;
                        if (first == null) first = ad;
                        if (name.IndexOf("sharkhub", StringComparison.OrdinalIgnoreCase) >= 0 &&
                            name.IndexOf("installer", StringComparison.OrdinalIgnoreCase) < 0) { pick = ad; break; }
                    }
                    if (pick == null) pick = first;
                    if (pick != null)
                    {
                        rel.AssetName = pick["name"] as string;
                        rel.Url = pick["browser_download_url"] as string;
                        rel.Size = Convert.ToInt64(pick["size"]);
                    }
                }
                if (rel.Url == null) throw new InvalidOperationException("The latest release at github.com/" + repo + " has no APK attached.");
                return rel;
            }
        }
    }

    enum Mood { Busy, Good, Bad, Info }

    class InstallerWindow
    {
        public readonly Window Window;
        readonly Button gitHubButton, fileButton, installButton, detailsButton, scanButton;
        readonly WrapPanel foundPanel;
        readonly TextBox ipBox, logBox;
        readonly TextBlock ipPlaceholder, ipHint, apkName, apkInfo, apkGlyph, statusText, statusGlyph, installLabel;
        readonly Border statusPanel, progressFill;
        readonly Grid progressTrack;
        readonly TranslateTransform progressShift;

        string apkPath;
        bool apkIsSharkHub;
        bool busy;

        const string SharkHubPackage = "com.chris.sharkhub";

        public InstallerWindow()
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("Installer.xaml"))
                Window = (Window)XamlReader.Load(s);

            gitHubButton = (Button)Window.FindName("GitHubButton");
            fileButton = (Button)Window.FindName("FileButton");
            installButton = (Button)Window.FindName("InstallButton");
            detailsButton = (Button)Window.FindName("DetailsButton");
            scanButton = (Button)Window.FindName("ScanButton");
            foundPanel = (WrapPanel)Window.FindName("FoundPanel");
            ipBox = (TextBox)Window.FindName("IpBox");
            logBox = (TextBox)Window.FindName("LogBox");
            ipPlaceholder = (TextBlock)Window.FindName("IpPlaceholder");
            ipHint = (TextBlock)Window.FindName("IpHint");
            apkName = (TextBlock)Window.FindName("ApkName");
            apkInfo = (TextBlock)Window.FindName("ApkInfo");
            apkGlyph = (TextBlock)Window.FindName("ApkGlyph");
            statusText = (TextBlock)Window.FindName("StatusText");
            statusGlyph = (TextBlock)Window.FindName("StatusGlyph");
            installLabel = (TextBlock)Window.FindName("InstallLabel");
            statusPanel = (Border)Window.FindName("StatusPanel");
            progressFill = (Border)Window.FindName("ProgressFill");
            progressTrack = (Grid)Window.FindName("ProgressTrack");
            progressShift = (TranslateTransform)Window.FindName("ProgressShift");

            var logo = LoadPng("logo.png");
            ((Image)Window.FindName("Logo")).Source = logo;
            var icon = LoadPng("icon.png");
            if (icon != null) Window.Icon = icon;

            // fit short laptop screens; the content scrolls when it has to
            Window.Height = Math.Min(Window.Height, SystemParameters.WorkArea.Height - 24);

            Window.SourceInitialized += (s, e) => DarkTitleBar();
            gitHubButton.Click += async (s, e) => await DownloadLatestAsync();
            fileButton.Click += (s, e) => ChooseFile();
            installButton.Click += async (s, e) => await InstallAsync();
            scanButton.Click += async (s, e) => await FindCarAsync();
            detailsButton.Click += (s, e) =>
            {
                bool show = logBox.Visibility != Visibility.Visible;
                logBox.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
                detailsButton.Content = show ? "Hide details" : "Details";
            };
            ipBox.TextChanged += (s, e) => Refresh();
            // drop an APK anywhere on the window to choose it
            Window.AllowDrop = true;
            Window.DragOver += (s, e) =>
            {
                e.Effects = !busy && DroppedApk(e.Data) != null ? DragDropEffects.Copy : DragDropEffects.None;
                e.Handled = true;
            };
            Window.Drop += (s, e) => { var apk = DroppedApk(e.Data); if (apk != null && !busy) Preselect(apk); };
            ipBox.KeyDown += async (s, e) => { if (e.Key == Key.Enter && installButton.IsEnabled) await InstallAsync(); };

            ipBox.Text = Store.Get("ip") ?? "";
            Refresh();
        }

        // ---------------------------------------------------------------- state

        static readonly Regex IpPattern = new Regex(@"^\s*(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:(\d{1,5}))?\s*$");

        /// <summary>"ip:port" from the box, 5555 when no port is given; null when it isn't an address.</summary>
        string Target()
        {
            var m = IpPattern.Match(ipBox.Text);
            if (!m.Success) return null;
            for (int i = 1; i <= 4; i++) if (int.Parse(m.Groups[i].Value) > 255) return null;
            int port = m.Groups[6].Success ? int.Parse(m.Groups[6].Value) : 5555;
            if (port < 1 || port > 65535) return null;
            return string.Format("{0}.{1}.{2}.{3}:{4}", m.Groups[1].Value, m.Groups[2].Value, m.Groups[3].Value, m.Groups[4].Value, port);
        }

        void Refresh()
        {
            ipPlaceholder.Visibility = ipBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
            bool ipOk = Target() != null;
            ipHint.Text = ipBox.Text.Trim().Length > 0 && !ipOk
                ? "That doesn't look like an IP address. It's four numbers with dots, like 192.168.43.120."
                : "Port 5555 is used unless you add one, like 10.0.0.5:5555.";
            ipHint.Foreground = ipBox.Text.Trim().Length > 0 && !ipOk ? Brush("#FF6B5E") : Brush("#8DA1BF");
            // stays lit while an install runs (it reads "Installing…" and ignores clicks)
            installButton.IsEnabled = busy || (ipOk && apkPath != null && File.Exists(apkPath));
            gitHubButton.IsEnabled = !busy;
            fileButton.IsEnabled = !busy;
            scanButton.IsEnabled = !busy;
            foundPanel.IsEnabled = !busy;
            ipBox.IsEnabled = !busy;
        }

        // ---------------------------------------------------------------- finding the car

        async Task FindCarAsync()
        {
            if (busy) return;
            busy = true; Refresh();
            foundPanel.Children.Clear();
            foundPanel.Visibility = Visibility.Collapsed;
            try
            {
                Status(Mood.Busy, "Looking for the car on this PC's Wi-Fi…");
                foreach (var line in Finder.Describe()) Log("scan " + line);
                Progress(0);
                var hits = await Finder.ScanAsync((d, n) => Window.Dispatcher.BeginInvoke(new Action(() => Progress(n > 0 ? d / (double)n : 1))));
                foreach (var h in hits) Log("found " + h.Ip + (h.Adb ? " (ADB)" : " (port 5555 open, no ADB reply)"));
                var adb = hits.Where(h => h.Adb).ToList();
                var list = adb.Count > 0 ? adb : hits;
                if (list.Count == 0)
                    Status(Mood.Bad, "No car found. Check ADB is switched on (step 3) and the car and this PC are on the same Wi-Fi (step 4).");
                else if (list.Count == 1)
                {
                    ipBox.Text = list[0].Ip;
                    Status(Mood.Good, list[0].Adb ? "Found the car at " + list[0].Ip + "." : "Something at " + list[0].Ip + " has the ADB port open. It's probably the car.");
                }
                else
                {
                    ShowFound(list);
                    Status(Mood.Info, "Found " + list.Count + " devices with ADB switched on. Tap the one that's the car.");
                }
            }
            catch (Exception e)
            {
                Status(Mood.Bad, "The scan didn't work: " + Friendly(e));
                Log(e.ToString());
            }
            finally
            {
                busy = false; Refresh();
                NoProgress();
            }
        }

        void ShowFound(List<Finder.Hit> hits)
        {
            foundPanel.Children.Clear();
            foreach (var h in hits)
            {
                var b = new Button();
                b.Style = (Style)Window.FindResource("ChipButton");
                b.Content = h.Adb ? h.Ip : h.Ip + "  ?";
                var ip = h.Ip;
                b.Click += (s, e) => { ipBox.Text = ip; ipBox.Focus(); };
                foundPanel.Children.Add(b);
            }
            foundPanel.Visibility = Visibility.Visible;
        }

        void SetApk(string path, string info, bool isSharkHub)
        {
            apkPath = path;
            apkIsSharkHub = isSharkHub;
            apkName.Text = Path.GetFileName(path);
            apkInfo.Text = info;
            apkGlyph.Text = "";
            apkGlyph.Foreground = Brush("#2EB8FF");
            Refresh();
        }

        // ---------------------------------------------------------------- the app

        public void Preselect(string path)
        {
            if (string.IsNullOrEmpty(path) || !path.EndsWith(".apk", StringComparison.OrdinalIgnoreCase) || !File.Exists(path)) return;
            bool sharkHub = Path.GetFileName(path).IndexOf("shark", StringComparison.OrdinalIgnoreCase) >= 0;
            SetApk(path, Mb(new FileInfo(path).Length) + " · from this PC", sharkHub);
        }

        static string DroppedApk(IDataObject data)
        {
            if (!data.GetDataPresent(DataFormats.FileDrop)) return null;
            var files = data.GetData(DataFormats.FileDrop) as string[];
            return files == null ? null : files.FirstOrDefault(f => f.EndsWith(".apk", StringComparison.OrdinalIgnoreCase));
        }

        void ChooseFile()
        {
            var dlg = new OpenFileDialog();
            dlg.Title = "Choose the APK to install";
            dlg.Filter = "Android apps (*.apk)|*.apk|All files (*.*)|*.*";
            var last = Store.Get("lastApkDir");
            if (last != null && Directory.Exists(last)) dlg.InitialDirectory = last;
            if (dlg.ShowDialog(Window) != true) return;
            Store.Set("lastApkDir", Path.GetDirectoryName(dlg.FileName));
            Preselect(dlg.FileName);
        }

        async Task DownloadLatestAsync()
        {
            var repo = Store.Repo;
            if (string.IsNullOrEmpty(repo))
            {
                Status(Mood.Info, "Shark Hub's GitHub releases aren't published yet. Choose an APK file for now.");
                return;
            }
            busy = true; Refresh();
            try
            {
                EnableTls12();
                Status(Mood.Busy, "Looking for the latest Shark Hub…");
                Progress(null);
                var rel = await GitHub.LatestAsync(repo);
                Log("Latest release " + rel.Tag + ": " + rel.AssetName + " (" + Mb(rel.Size) + ")");
                var dir = Path.Combine(Store.Dir, "downloads");
                Directory.CreateDirectory(dir);
                var file = Path.Combine(dir, rel.AssetName);
                if (!File.Exists(file) || new FileInfo(file).Length != rel.Size)
                {
                    Status(Mood.Busy, "Downloading Shark Hub " + rel.Tag + "…");
                    Progress(0);
                    var part = file + ".part";
                    using (var wc = new WebClient())
                    {
                        wc.Headers[HttpRequestHeader.UserAgent] = "SharkHubInstaller";
                        wc.DownloadProgressChanged += (s, e) =>
                        {
                            long total = e.TotalBytesToReceive > 0 ? e.TotalBytesToReceive : rel.Size;
                            if (total > 0) Window.Dispatcher.BeginInvoke(new Action(() => Progress(e.BytesReceived / (double)total)));
                        };
                        await wc.DownloadFileTaskAsync(new Uri(rel.Url), part);
                    }
                    if (File.Exists(file)) File.Delete(file);
                    File.Move(part, file);
                }
                SetApk(file, Mb(rel.Size) + " · Shark Hub " + rel.Tag + " from GitHub", true);
                Status(Mood.Good, "Shark Hub " + rel.Tag + " is ready to install.");
            }
            catch (Exception e)
            {
                Status(Mood.Bad, "Couldn't get the latest Shark Hub: " + Friendly(e));
                Log(e.ToString());
            }
            finally
            {
                busy = false; Refresh();
                NoProgress();
            }
        }

        // ---------------------------------------------------------------- install

        async Task InstallAsync()
        {
            var target = Target();
            if (target == null || apkPath == null || busy) return;
            busy = true; Refresh();
            installLabel.Text = "Installing…";
            Store.Set("ip", ipBox.Text.Trim());
            try
            {
                // 1. adb
                var adb = Adb.Find();
                if (adb == null)
                {
                    EnableTls12();
                    Status(Mood.Busy, "Fetching Google's adb (about 7 MB, first time only)…");
                    Progress(0);
                    adb = await Adb.DownloadAsync(f => Window.Dispatcher.BeginInvoke(new Action(() => Progress(f))));
                }
                Log("adb: " + adb);
                Status(Mood.Busy, "Starting adb…");
                Progress(null);
                await Adb.StartServerAsync(adb);

                // 2. connect, then wait for the car to trust this PC
                Status(Mood.Busy, "Connecting to the car at " + target + "…");
                var c = await Adb.RunAsync(adb, "connect " + target, 20000);
                Log("> adb connect " + target + "\n" + c.Output);
                if (c.Output.IndexOf("connected to", StringComparison.OrdinalIgnoreCase) < 0 &&
                    c.Output.IndexOf("failed to authenticate", StringComparison.OrdinalIgnoreCase) < 0)
                {
                    Status(Mood.Bad, "Couldn't reach the car at " + target + ". Check ADB is switched on and the car and this PC are on the same Wi-Fi.");
                    return;
                }
                if (!await WaitForAuthorisationAsync(adb, target)) return;

                // 3. install
                var size = new FileInfo(apkPath).Length;
                Status(Mood.Busy, "Sending " + Path.GetFileName(apkPath) + " (" + Mb(size) + ") to the car. This takes a minute or so over Wi-Fi…");
                Progress(null);
                var install = await Adb.RunAsync(adb, "-s " + target + " install -r -g \"" + apkPath + "\"", 15 * 60 * 1000);
                Log("> adb install -r -g " + Path.GetFileName(apkPath) + "\n" + install.Output);
                if (install.Output.IndexOf("Success", StringComparison.Ordinal) < 0)
                {
                    Status(Mood.Bad, InstallFailure(install.Output));
                    return;
                }

                // 4. open it
                if (apkIsSharkHub)
                {
                    var open = await Adb.RunAsync(adb, "-s " + target + " shell monkey -p " + SharkHubPackage + " -c android.intent.category.LAUNCHER 1", 20000);
                    Log("> open Shark Hub\n" + open.Output);
                    Status(Mood.Good, "Installed. Shark Hub is opening on the car.");
                }
                else
                {
                    Status(Mood.Good, "Installed on the car.");
                }
            }
            catch (Exception e)
            {
                Status(Mood.Bad, "Something went wrong: " + Friendly(e));
                Log(e.ToString());
            }
            finally
            {
                busy = false; Refresh();
                installLabel.Text = "Install on the car";
                NoProgress();
            }
        }

        /// <summary>
        /// The first connection from a PC makes the car ask "Allow USB debugging?". Poll until it's
        /// accepted (state "device"), reconnecting if the car dropped us while it waited.
        /// </summary>
        async Task<bool> WaitForAuthorisationAsync(string adb, string target)
        {
            var until = DateTime.UtcNow.AddSeconds(90);
            while (DateTime.UtcNow < until)
            {
                var st = await Adb.RunAsync(adb, "-s " + target + " get-state", 10000);
                var o = st.Output;
                if (o.Trim() == "device") return true;
                if (o.IndexOf("unauthorized", StringComparison.OrdinalIgnoreCase) >= 0 || o.IndexOf("authenticat", StringComparison.OrdinalIgnoreCase) >= 0)
                    Status(Mood.Info, "Look at the car: tap Allow on \"Allow USB debugging?\" (tick Always allow).");
                else if (o.IndexOf("not found", StringComparison.OrdinalIgnoreCase) >= 0 || o.IndexOf("offline", StringComparison.OrdinalIgnoreCase) >= 0)
                    Log((await Adb.RunAsync(adb, "connect " + target, 20000)).Output);
                await Task.Delay(1500);
            }
            Status(Mood.Bad, "The car didn't allow the connection. Press Install again and tap Allow on the car's screen.");
            return false;
        }

        static string InstallFailure(string output)
        {
            var m = Regex.Match(output, @"(INSTALL_[A-Z_]+)");
            var code = m.Success ? m.Groups[1].Value : null;
            if (code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                return "The car has a copy of this app signed with a different key. Uninstall it on the car, then install again.";
            if (code == "INSTALL_FAILED_VERSION_DOWNGRADE")
                return "The car already has a newer version of this app.";
            if (code == "INSTALL_FAILED_INSUFFICIENT_STORAGE")
                return "There isn't enough free storage on the car.";
            if (code != null && (code.StartsWith("INSTALL_PARSE_FAILED") || code == "INSTALL_FAILED_INVALID_APK"))
                return "That file isn't a valid Android app.";
            if (code == "INSTALL_FAILED_USER_RESTRICTED" || code == "INSTALL_FAILED_VERIFICATION_FAILURE")
                return "The car refused the install (" + code + ").";
            if (output.IndexOf("timed out", StringComparison.OrdinalIgnoreCase) >= 0)
                return "The install timed out. Check the Wi-Fi and try again.";
            return "The install didn't finish" + (code != null ? " (" + code + ")." : ". See Details.");
        }

        // ---------------------------------------------------------------- status & progress

        void Status(Mood mood, string text)
        {
            statusPanel.Visibility = Visibility.Visible;
            statusText.Text = text;
            switch (mood)
            {
                case Mood.Busy: statusGlyph.Text = ""; statusGlyph.Foreground = Brush("#2EB8FF"); break;
                case Mood.Good: statusGlyph.Text = ""; statusGlyph.Foreground = Brush("#3DDC97"); break;
                case Mood.Bad: statusGlyph.Text = ""; statusGlyph.Foreground = Brush("#FF6B5E"); break;
                default: statusGlyph.Text = ""; statusGlyph.Foreground = Brush("#FFB547"); break;
            }
            Log(text);
        }

        void Log(string line)
        {
            logBox.AppendText(DateTime.Now.ToString("HH:mm:ss") + "  " + line.Trim() + Environment.NewLine);
            logBox.ScrollToEnd();
        }

        /// <summary>A fraction fills the bar; null runs the sliding indeterminate bar.</summary>
        void Progress(double? fraction)
        {
            if (progressTrack.Visibility != Visibility.Visible)
            {
                progressTrack.Visibility = Visibility.Visible;
                Window.UpdateLayout();
            }
            double width = progressTrack.ActualWidth > 0 ? progressTrack.ActualWidth : 480;
            if (fraction.HasValue)
            {
                progressShift.BeginAnimation(TranslateTransform.XProperty, null);
                progressShift.X = 0;
                progressFill.Width = Math.Max(6, width * Math.Min(1, Math.Max(0, fraction.Value)));
            }
            else
            {
                progressFill.Width = width * 0.3;
                var slide = new DoubleAnimation(-width * 0.3, width, new Duration(TimeSpan.FromSeconds(1.4)));
                slide.RepeatBehavior = RepeatBehavior.Forever;
                slide.EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut };
                progressShift.BeginAnimation(TranslateTransform.XProperty, slide);
            }
        }

        void NoProgress()
        {
            progressShift.BeginAnimation(TranslateTransform.XProperty, null);
            progressTrack.Visibility = Visibility.Collapsed;
        }

        // ---------------------------------------------------------------- snapshot

        public int Snapshot(string path, string state, string apk)
        {
            Window.WindowStartupLocation = WindowStartupLocation.Manual;
            Window.Left = -20000;
            Window.Top = 0;
            Window.Height = 1000;
            Window.ShowInTaskbar = false;
            Window.ShowActivated = false;
            Window.Show();
            if (state != "idle")
            {
                if (apk != null && File.Exists(apk))
                    SetApk(apk, Mb(new FileInfo(apk).Length) + " · from this PC", true);
                ipBox.Text = "10.175.146.136";
            }
            Window.UpdateLayout();
            if (state == "allow") Status(Mood.Info, "Look at the car: tap Allow on \"Allow USB debugging?\" (tick Always allow).");
            if (state == "found")
            {
                ipBox.Text = "";
                var a = new Finder.Hit(); a.Ip = "10.175.146.136"; a.Adb = true;
                var b = new Finder.Hit(); b.Ip = "10.175.146.201"; b.Adb = true;
                ShowFound(new List<Finder.Hit> { a, b });
                Status(Mood.Info, "Found 2 devices with ADB switched on. Tap the one that's the car.");
            }
            if (state == "busy")
            {
                busy = true; Refresh();
                installLabel.Text = "Installing…";
                Status(Mood.Busy, "Sending " + Path.GetFileName(apkPath ?? "SharkHub.apk") + " to the car. This takes a minute or so over Wi-Fi…");
                Window.UpdateLayout();
                Progress(0.45);
            }
            if (state == "done") Status(Mood.Good, "Installed. Shark Hub is opening on the car.");
            Window.UpdateLayout();

            var root = (Panel)Window.Content;
            root.Background = Window.Background;
            root.UpdateLayout();
            const double scale = 2;
            var rtb = new RenderTargetBitmap((int)(root.ActualWidth * scale), (int)(root.ActualHeight * scale), 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            rtb.Render(root);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(rtb));
            using (var f = File.Create(path)) enc.Save(f);
            Window.Close();
            return 0;
        }

        // ---------------------------------------------------------------- helpers

        static string Mb(long bytes) { return (bytes / 1048576.0).ToString("0.0") + " MB"; }

        static string Friendly(Exception e)
        {
            var we = e as WebException;
            if (we != null && we.Status == WebExceptionStatus.NameResolutionFailure) return "this PC seems to be offline.";
            var inner = e;
            while (inner.InnerException != null) inner = inner.InnerException;
            return inner.Message;
        }

        static void EnableTls12() { ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12; }

        static SolidColorBrush Brush(string hex) { return (SolidColorBrush)new BrushConverter().ConvertFromString(hex); }

        static BitmapImage LoadPng(string name)
        {
            var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name);
            if (s == null) return null;
            var img = new BitmapImage();
            img.BeginInit();
            img.CacheOption = BitmapCacheOption.OnLoad;
            img.StreamSource = s;
            img.EndInit();
            img.Freeze();
            s.Dispose();
            return img;
        }

        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

        /// <summary>Dark title bar to match the window (Windows 10 20H1+); caption colour on Windows 11.</summary>
        void DarkTitleBar()
        {
            try
            {
                var hwnd = new WindowInteropHelper(Window).Handle;
                int on = 1;
                if (DwmSetWindowAttribute(hwnd, 20, ref on, 4) != 0) DwmSetWindowAttribute(hwnd, 19, ref on, 4);
                int caption = 0x00160A04;   // #040A16 as COLORREF (0x00BBGGRR)
                DwmSetWindowAttribute(hwnd, 35, ref caption, 4);
            }
            catch { }
        }
    }
}
