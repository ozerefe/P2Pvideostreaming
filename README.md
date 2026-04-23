# P2P Video Streaming

CSE471 dersi kapsamında geliştirilen, Java Swing tabanlı ve P2P mimarisiyle çalışan bir video akış uygulamasıdır. Peer'ler UDP ile birbirini keşfeder, TCP üzerinden video chunk transferi yapar ve VLCJ ile oynatma tarafını yönetir.

## Ozellikler

- UDP discovery ile otomatik peer bulma
- TCP chunk tabanli video transferi
- Dagitik video arama (SEARCH / SEARCH_REPLY)
- Subnet ve gateway destegi (subnet'ler arasi iletim)
- Swing arayuz ile katalog, arama ve oynatma yonetimi
- Exclusion filtreleriyle belirli icerikleri engelleme

## Teknolojiler

- Java 17
- Maven
- Swing
- VLCJ
- Gson
- SLF4J

## Gereksinimler

- JDK 17+
- Maven 3.8+
- VLC (macOS icin varsayilan yol: `/Applications/VLC.app/Contents/MacOS/lib`)

> Not: `Main` sinifi `jna.library.path` degerini macOS VLC yoluna sabitliyor. Farkli bir isletim sistemi kullaniyorsaniz bu yolu ortama gore guncelleyin.

## Projeyi Calistirma

### 1) Derleme

```bash
mvn clean compile
```

### 2) Tek peer baslatma

```bash
mvn exec:java -Dexec.args="--udpPort 50000 --tcpPort 50001 --peerName Peer1 --subnetId Subnet-A"
```

### 3) Hazir scriptlerle coklu peer baslatma

```bash
./run-peer1.sh
./run-peer2.sh
./run-peer3.sh
```

Scriptlerdeki varsayilan roller:

- `run-peer1.sh`: Subnet-A icinde normal peer
- `run-peer2.sh`: Ikinci peer
- `run-peer3.sh`: Gateway peer (`--isGateway true`)

## Komut Satiri Argumanlari

- `--udpPort <port>`: Discovery UDP portu
- `--tcpPort <port>`: Chunk transfer TCP portu
- `--peerName <name>`: Peer gorunen adi
- `--subnetId <id>`: Peer subnet bilgisi (orn. `Subnet-A`)
- `--isGateway <true|false>`: Gateway rolu
- `--gatewaySubnets <list>`: Gateway bagli subnet listesi (virgulle ayrilmis)
- `--help`: Yardim

## Mimari Ozet

- `DiscoveryService`: UDP tabanli kesif, arama ve cevap mesajlarinin yonetimi
- `ChunkServer` / `ChunkClient`: TCP ile chunk bazli veri transferi
- `VideoCatalog`: Lokal video tarama, hashleme ve metadata yonetimi
- `PeerManager`: Aktif peer takibi, timeout temizligi ve video-peer eslestirmesi
- `DownloadSession`: Streaming oturum yonetimi
- `MainFrame`: Swing UI

## Paketleme

Calistirilabilir/fat-jar uretmek icin:

```bash
mvn clean package
```

## Gelistirme Notlari

- Bu depo egitim/proje amaclidir.
- Ag ortaminda test ederken firewall/port izinlerini kontrol edin.
