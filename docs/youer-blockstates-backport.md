# Backport de BlockStates para Youer 1.21.1

## Referencias y alcance

Base: `a6de80ae`, fork de WorldEdit 7.3.8. Implementación verificada con Temurin 21.0.11 y Gradle 8.9.

Cambios de referencia de EngineHub:

- [296613424f10a970834bd0a50d70f129bb53335b — Re-implement backing state maps](https://github.com/EngineHub/WorldEdit/commit/296613424f10a970834bd0a50d70f129bb53335b).
- [9d74089a1f657c4f19c9cbc8cfc43ef28164c723 — Only load the LegacyMapper when needed](https://github.com/EngineHub/WorldEdit/commit/9d74089a1f657c4f19c9cbc8cfc43ef28164c723).

El backport añade las tres clases `*BlockTypeStateList`, sustituye los mapas de combinaciones y tablas de vecinos por índices y strides, y mantiene mapas de valores compactos con claves compartidas por tipo. `BaseBlock` y la representación string conservan su estrategia anterior.

Adaptaciones deliberadas para 7.3.8:

- Se mantienen `Property.getName()` / `getValues()` y la igualdad de propiedades por nombre. La comparación por identidad de upstream 7.4 no se traslada a esta API.
- Se conserva el orden cartesiano anterior: cambia primero la última propiedad. Esto también conserva el estado inicial y el orden del string de propiedades.
- Se conserva la lógica original de `equalsFuzzy`, incluidos sus accesos mediante métodos públicos.
- `FuzzyBlockState` recibe una copia defensiva de los valores del builder; reutilizarlo no modifica estados existentes.
- El cronómetro cubre toda la generación y usa tiempo monotónico. Se atiende al watchdog cada 1024 estados. Los productos fuera del rango de `int` y las propiedades sin valores fallan antes de asignar el array de estados.
- `loadMappings()` conserva los datos bundled de 7.3.8 y deja de forzar `LegacyMapper`. No queda vacío porque 7.3.8 aún tiene esas otras responsabilidades.
- Parsers y configuración validan los IDs numéricos antes de obtener el mapper. Su primera inicialización está sincronizada para que solicitudes concurrentes no dupliquen la carga.

`Capability.WORLD_EDITING.ready()` sigue recorriendo todos los estados para registrar sus IDs nativos. El índice local de la lista **no sustituye** esos IDs. Por ello se espera que los 753.818 estados sigan materializados en el servidor descrito.

## Artefactos y comprobaciones

Candidato: `worldedit-bukkit/build/libs/worldedit-youer-7.3.8-state-indexed-1.jar`.

SHA-256: `196F5C7B4F46119FE03259522B1B6A2AA9DA9551AAE3103661F4C79049C6D28C`.

Referencia local: `build/backport/baseline/worldedit-youer-7.3.8-2.jar`.

SHA-256 de referencia: `D01D745E1370487C5764E62AB3AA299B9E53199EFE3F4E86303DD7D3F9721E1D`.

- Suite core: **253 casos, 252 aprobados, 1 omitido, 0 fallos**. El omitido es `BlockTransformExtentTest`, ya deshabilitado en el repositorio original por requerir una plataforma.
- Se añadieron 13 pruebas de transiciones, enumeración, propiedades equivalentes, valores inválidos, singleton, inmutabilidad, defaults, fuzzy, NBT, cardinalidad alta, overflow y carga legacy.
- Checkstyle main/test, licencias y empaquetado Bukkit: aprobados.
- Firmas públicas de `BlockState`, `BlockType` y `FuzzyBlockState`: idénticas según `javap -public`.
- Las 65 clases del adaptador 1.21 incluidas en ambos JAR son idénticas byte por byte.
- El JAR contiene las tres clases nuevas y fastutil reubicado. `javap -p` confirma que `BlockState` ya no tiene campo `Table`, `populate()` ni `generateStateMap()`.

Comandos desde la raíz, usando Java 21:

```powershell
.\gradlew.bat :worldedit-core:test :worldedit-core:checkstyleMain :worldedit-core:checkstyleTest :worldedit-bukkit:shadowJar --console=plain
.\gradlew.bat :worldedit-core:checkLicenses --console=plain
```

Las licencias se ejecutan por separado: el build existente tiene una dependencia no declarada entre `checkLicenseTest` y la salida de ANTLR al reunir ambas tareas en una sola invocación. No se alteró la configuración general de Gradle por ese problema.

## Comparación sintética reproducible

El programa en `tools/blockstate-benchmark` ejecuta el mismo código contra cada JAR en JVM independientes, con `-Xms512m -Xmx2g -XX:+UseG1GC`. Construye dos bloques artificiales de 24.576 y 22.400 estados (46.976 en total). Las cardinalidades coinciden con los ejemplos del informe, pero **no reproduce las propiedades reales de esos mods**.

Mide el incremento de heap usado después de tres GC explícitos, reteniendo los tipos; registra por separado el tiempo de generación, la procedencia de `BlockState` y una huella de todas las combinaciones en orden. Es una comprobación del diseño, no una medición del servidor, de RSS ni del pico de asignaciones. Los tiempos son orientativos: no es un benchmark JMH.

```powershell
.\gradlew.bat :worldedit-core:writeProbeClasspath -I tools/blockstate-benchmark/runtime-classpath.init.gradle
.\tools\blockstate-benchmark\run.ps1 `
  -BaselineJar build/backport/baseline/worldedit-youer-7.3.8-2.jar `
  -CandidateJar worldedit-bukkit/build/libs/worldedit-youer-7.3.8-state-indexed-1.jar `
  -JavaHome 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
```

Cada ejecución guarda resultados, versión Java y hashes en `build/backport/probe-<fecha>`. Para reproducir en otro checkout hay que conservar o reconstruir el JAR base y usar la ruta del JDK 21 local.

Resultado local del 2026-10-06, tres JVM por variante, con el JAR candidato y hashes indicados arriba:

| Mediana | Referencia 7.3.8 | Candidato |
|---|---:|---:|
| Estados retenidos | 46.976 | 46.976 |
| Incremento de heap tras GC | 105,92 MiB | 13,15 MiB |
| Tiempo de generación | 22,93 s | 14,23 ms |

La reducción sintética de heap es **87,58 %**. Las seis ejecuciones producen la misma huella `-50669888864679999`. Los resultados crudos están en `tools/blockstate-benchmark/results-20261006.txt`. Estos tiempos corresponden a una sola generación por JVM después de un calentamiento pequeño; no deben extrapolarse a TPS, latencia de comandos ni tiempo total de arranque de Youer.

## Validación pendiente con el modpack completo

No se ha iniciado ni modificado un servidor Youer. No se ha validado aún el ahorro real de los 753.818 estados ni las operaciones de edición en Minecraft.

1. Usar una **copia de pruebas** del mundo y restaurar todos los mods. Fijar versión exacta de Youer, mods/plugins, Java, flags, distancia de simulación, mundo y carga de jugadores. Guardar sus hashes y el resultado del auditor de estados.
2. Preparar dos copias idénticas o restaurar el mismo snapshot entre ejecuciones. Instalar únicamente un JAR WorldEdit en `plugins`: referencia o candidato. Reiniciar completamente; no usar recarga de plugins.
3. Hacer al menos tres ejecuciones de cada variante, alternando A/B. Medir en dos puntos: reposo tras arranque y después de la misma secuencia de ediciones. Usar iguales tiempos de espera y carga de chunks. Registrar tiempo de arranque, GC, heap vivo tras GC, máximo observado de heap y memoria del proceso; reportar committed por separado.
4. Ejecutar en una región de prueba `//set`, `//replace`, copiar/pegar, rotaciones, `//undo`, `//redo`, guardar/cargar schematics modernos y cargar uno legacy. Incluir bloques vanilla, los dos bloques de alta cardinalidad indicados en el informe, propiedades no predeterminadas y block entities con NBT. Comparar estados/NBT antes, después y tras undo, y registrar errores del adaptador, ClassCastException y watchdog.
5. Comprobar que nombres modernos/configuración moderna no cargan legacy durante el arranque; probar después IDs numéricos y schematics legacy. El primer uso puede asumir el coste de carga que antes se pagaba al inicio.
6. Capturar histogramas y, si es posible, dumps en ambos puntos. Ejemplo con el JDK del servidor, sustituyendo `12345` por su PID:

```text
jcmd 12345 VM.flags
jcmd 12345 GC.run
jcmd 12345 GC.heap_info
jcmd 12345 GC.class_histogram
jcmd 12345 GC.heap_dump /ruta/absoluta/candidato-arranque.hprof
```

Los dumps y GC explícitos pueden pausar la JVM: ejecutar en la copia de pruebas y usar el mismo procedimiento para ambas variantes. Guardar cada salida con variante, repetición y fase.

En el analizador de heap, comparar memoria retenida por WorldEdit y rutas de retención desde sus `BlockState`, no solo los totales globales de clases Guava: otros mods también las usan. La señal estructural esperada es **cero tablas de vecinos retenidas por estados de WorldEdit**; pueden seguir existiendo `SparseImmutableTable` de otros componentes.

Aceptación: mismos estados/resultados, ningún error nuevo, descenso repetible del heap vivo atribuible a WorldEdit y sin regresión relevante de arranque o edición. Los aproximadamente 1,5 GiB del experimento de retirada de mods no son una promesa del ahorro de este parche.

Reversión: detener la copia de pruebas, retirar el JAR candidato, restaurar el JAR de referencia y el snapshot del mundo de esa ejecución; reiniciar. Conservar logs y dumps de la ejecución fallida.

## Segunda pasada (7.3.8-4fix): valores de propiedades virtuales

Después de M1 cada `BlockState` no-singleton seguía guardando un `Object2ObjectArrayMap` envuelto en `Object2ObjectMaps.unmodifiable`, más su `Object[]` de valores. Además, el `EntrySet` y el `UnmodifiableSet` se materializaban en todos los estados porque `hashCode()` hacía `Objects.hash(blockType, values)`. Esa información ya está codificada en `stateListIndex` y en la `stride` de cada propiedad.

Cambios:

- `BlockState` guarda solo `blockType`, su `BlockTypeStateList` e índice. `getState()` y `with()` derivan el valor con `(index / stride) % valueCount`, sin allocations.
- `getStates()` devuelve una vista inmutable `BlockStateValuesView`, creada en la primera llamada y reutilizada después. Cumple el contrato de `Map` (`equals`/`hashCode`) y conserva el orden de declaración.
- `hashCode()` (`31 * blockType.hashCode() + index`, sin `Integer` cacheado), `equals()`/`equalsFuzzy()` (comparación por índice dentro de la misma lista, sin `HashSet`) y `getAsString()` no tocan la vista.
- `FuzzyBlockState` conserva su propio mapa: no son estados por combinación.

Probe sintético (mismos 46.976 estados, tres rondas alternadas, `results-20261006-4fix.txt`):

| Métrica | M1 (`state-indexed-1`) | 4fix |
|---|---:|---:|
| Heap retenido | ~13,78 MB (~293 B/estado) | ~8,09 MB (~172 B/estado) |
| Tiempo de generación | ~13,1 ms | ~5,3 ms |

Huella idéntica (`-50669888864679999`) en todas las ejecuciones. El probe usa 5 propiedades por estado; en el modpack real el ahorro por estado será algo menor (`Object[]` más pequeños), aunque el `Integer` de `hashCode` eliminado no aparece en el probe. Criterio en el servidor: `spark heapsummary` con ~753.818 `BlockState` y `Object2ObjectArrayMap`, `Object2ObjectMaps$UnmodifiableMap`, `Object2ObjectArrayMap$EntrySet` y `ObjectSets$UnmodifiableSet` cerca de 0.
