# Extracción de Binarios OpenVPN

## Problema
Los binarios OpenVPN en el proyecto actual son stubs de ~3-4KB que no funcionan.

## Solución
Extraer los binarios reales de la app oficial **OpenVPN Connect**.

## Pasos

### 1. Descargar OpenVPN Connect APK
1. Ir a: https://www.apkmirror.com/apk/openvpn-connect/openvpn-connect/
2. Descargar la última versión
3. Elegir variante **arm64-v8a** o **armeabi-v7a** (depende de tu dispositivo)
4. Guardar el APK como `openvpn-connect.apk` en esta carpeta (`scripts/`)

### 2. Extraer binarios

#### Opción A: PowerShell (Windows)
```powershell
cd "C:\Users\tu-usuario\Desktop\vpn xuper\scripts"
.\extract_binaries.ps1
```

#### Opción B: Python
```bash
cd scripts
python extract_binaries.py openvpn-connect.apk
```

#### Opción C: Manual
1. Cambiar extensión del APK de `.apk` a `.zip`
2. Abrir con 7-Zip o WinRAR
3. Extraer:
   - `lib/arm64-v8a/*.so` → `android/app/src/main/jniLibs/arm64-v8a/`
   - `lib/armeabi-v7a/*.so` → `android/app/src/main/jniLibs/armeabi-v7a/`
   - `assets/pie_openvpn.*` → `android/app/src/main/assets/`

### 3. Binarios a extraer
Los archivos importantes son:
- `libopenvpn.so` - Core de OpenVPN (~4MB)
- `libovpn3.so` - OpenVPN 3
- `libovpnutil.so` - Utilidades
- `libosslutil.so` - OpenSSL utilities
- `pie_openvpn.arm64-v8a` - Binario ejecutable
- `pie_openvpn.armeabi-v7a` - Binario ejecutable

### 4. Rebuild del proyecto
```bash
flutter clean
flutter pub get
flutter build apk --release
```

## Verificación
Después de extraer, los tamaños de archivo deberían ser:
- `libopenvpn.so`: ~4-5 MB
- `libovpn3.so`: ~6 MB
- `pie_openvpn.*`: ~150-200 KB

Si ves archivos de ~3-4 KB, algo salió mal.

## Alternativa: Compilar desde código fuente
Si preferís compilar vos mismo, seguí las instrucciones en:
https://github.com/schwabe/ics-openvpn
