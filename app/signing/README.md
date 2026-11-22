# Penandatanganan APK & update (kenapa "package conflicts")

Android hanya membenarkan update jika APK baru **ditandatangani dengan kunci yang sama** dan `versionCode` **lebih tinggi**.
Sebelum ini CI menandatangani dengan kunci *debug* yang dijana rawak oleh setiap runner GitHub, dan `versionCode` tetap `315`,
jadi setiap release tidak boleh dipasang di atas yang lama.

Sekarang:
- Semua binaan guna kunci tetap `app/signing/qudroid.jks` (atau kunci anda sendiri melalui GitHub Secrets, diutamakan).
- `versionCode` dikira automatik daripada versi (`1.0.0-beta.8` -> `1000028`), jadi sentiasa naik.
- App menyemak tandatangan + versionCode sebelum memasang dan memberitahu sebabnya jika gagal.

## Guna kunci sendiri (disyorkan)
```bash
keytool -genkeypair -keystore my.jks -alias qudroid -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 my.jks   # salin hasilnya
```
GitHub > Settings > Secrets and variables > Actions, tambah: `QUDROID_KEYSTORE_BASE64`, `QUDROID_KEYSTORE_PASSWORD`,
`QUDROID_KEY_ALIAS`, `QUDROID_KEY_PASSWORD`. **Setelah dipilih, jangan tukar lagi** (menukar kunci memaksa semua pengguna nyahpasang sekali).

Jangan padam/tukar `app/signing/qudroid.jks` jika tidak guna secrets.
