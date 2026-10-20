# Scanner YCC4 V5

Aplicación Android offline para lectura de códigos de barras de HU y NP (Moldeo y Ensamble).

## Funciones
- Lectura mediante CameraX + ML Kit Barcode Scanner, sin OCR.
- Ensamble: HU `5` + 8 dígitos; Moldeo: `CFM` + 9 o 10 dígitos (validar longitud en planta).
- NP: 10 dígitos. Confirmar contenido RAW y prefijos de las etiquetas reales.
- Evita HU duplicadas en la base SQLite existente `etiquetas_v1.db`.
- Comparte texto con TAB entre HU y NP, LF entre registros.
- Exporta CSV HU,NP con todos los registros.
- Genera QR de 20 registros con navegación y codificación UTF-8.
- Impresión de QR actual o todos mediante Android Print Framework (impresora compatible o servicio de impresión).

## Compilación
Subir el contenido de esta carpeta al repositorio de GitHub. El workflow `.github/workflows/main.yml` compila el APK.

## Notas importantes
- El reconocimiento automático de NP requiere verificar los códigos reales: otros códigos numéricos de 10 dígitos podrían confundirse.
- La impresora térmica NYX integrada puede requerir SDK específico: el método incluido usa el sistema de impresión de Android.
- La base SQLite antigua se conserva por compatibilidad. Los registros antiguos pueden incluir más campos, pero V5 solo muestra HU y NP.
- La app no se ha probado en dispositivo físico; realizar pruebas de lectura, compilación, exportación e impresión antes de producción.
