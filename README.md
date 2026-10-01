# Post-contenido — Unidad 6: Antipatrones de Diseño

## Descripción

Repositorio del post-contenido de la Unidad 6 de Patrones de Diseño de
Software. Un único proyecto Spring Boot (`pedidos-service`) con dos partes:
diagnóstico y refactorización de un antipatrón combinado en `GestorPedidos`,
y diagnóstico y corrección de un segundo antipatrón introducido al hacer
crecer el mismo proyecto con tres campañas de descuento. En ambos casos el
antipatrón no se indicó de antemano: identificarlo con evidencia citada del
código fue parte explícita de la actividad.

## Cómo ejecutar

```bash
mvn spring-boot:run
mvn test
```

- **Consola H2:** `http://localhost:8080/h2-console` — JDBC URL
  `jdbc:h2:mem:pedidosdb;DB_CLOSE_DELAY=-1;MODE=LEGACY`, usuario `sa`, sin
  contraseña.
- El proyecto no expone un controlador REST; `GestorPedidos.procesarPedido()`
  se ejercita mediante `GestorPedidosTest`, una prueba de integración
  (`@SpringBootTest`) con cinco pedidos que cubren las tres rutas de
  validación y los cinco tipos de descuento (VIP, FRECUENTE, Black Friday,
  Corporativo, Volumen).

## Herramientas utilizadas

- Java 17, Spring Boot, Spring JDBC, Maven, H2 Database
- VS Code / IntelliJ IDEA, Git, GitHub

## Decisiones de diseño

### Parte 1 — `GestorPedidos`

**Antipatrón identificado: God Object con Spaghetti Code interno (violación
de SRP).** `GestorPedidos.procesarPedido()` mezclaba **seis responsabilidades
técnicas** distintas dentro de un único método de 94 líneas (36-129):

- **Líneas 36-44** — validación de stock (una consulta SQL por ítem).
- **Líneas 47-65** — validación de cliente y mora, con hasta **3 niveles de
  anidamiento condicional** (tipo de cliente → deuda pendiente → horario de
  corte) — el bloque más anidado de todo el método, más que el propio
  cálculo de descuento.
- **Líneas 68-73** — cálculo de subtotal (otra consulta SQL por ítem).
- **Líneas 77-93** — cálculo de descuento, con hasta **2 niveles de
  anidamiento** (tipo de cliente → tramo de subtotal o historial).
- **Líneas 99-113** — persistencia directa vía JDBC, sin transacción
  explícita ni repositorio.
- **Líneas 116-129** — notificación, con el cuerpo del correo construido
  como texto plano dentro del mismo método.

Son seis responsabilidades con **motivos de cambio independientes** que
violan el Principio de Responsabilidad Única, y que además operan en **tres
niveles de abstracción simultáneos** en la misma secuencia de líneas: SQL
embebido (infraestructura), reglas de negocio (`subtotal > 1_000_000`), y
formato de texto para humanos (`"Estimado cliente,\n\n"`) — tres tipos de
conocimiento que rara vez dominan las mismas personas en un equipo real, lo
que dificulta tanto la lectura como las pruebas (no se puede verificar el
texto del correo sin tener una base de datos conectada).

**Hallazgo verificado — dependencia no determinista del reloj:** la
validación de mora usa `LocalTime.now()` para decidir si el cliente está
dentro o fuera del horario de corte (20:00), así que el resultado de esa
regla depende del instante exacto en que se ejecuta la prueba. Se comprobó
ejecutando `evaluaClienteMorosoSegunHorarioDeCorte` en dos momentos distintos
del día: a las 23:33 (`fueraDeHorario=true`, pedido confirmado
excepcionalmente) y a las 11:41 (`fueraDeHorario=false`, pedido rechazado por
deuda pendiente) — ambos resultados correctos según la regla de negocio, pero
evidencia de que una regla de negocio mezclada con el reloj del sistema en el
mismo nivel de abstracción produce un comportamiento no determinista entre
corridas de prueba.

**Hallazgo verificado — código muerto por un supuesto incorrecto sobre
`queryForObject`:** la validación de cliente (línea 47) asume que
`jdbcTemplate.queryForObject(...)` devuelve `null` cuando el cliente no
existe, pero el comportamiento real y documentado de Spring JDBC es lanzar
`EmptyResultDataAccessException` cuando la consulta no encuentra ninguna
fila. La rama `if (tipoCliente == null)` nunca se alcanza: un cliente
inexistente provoca una excepción no controlada en vez del rechazo limpio
que el método aparenta implementar. Se comprobó ejecutando
`rechazaPorClienteInexistente` contra un `clienteId` inexistente, confirmando
`EmptyResultDataAccessException` en vez de un `ResultadoPedido` rechazado —
otra señal de que un método de 94 líneas con tres niveles de abstracción
mezclados impidió que su propio autor verificara el comportamiento real de
una de sus seis responsabilidades.

**Costo de extender el sistema:** agregar un nuevo tipo de cliente (p. ej.
`"EMPLEADO"` con 20% fijo) exigiría insertar una rama `else if` dentro de un
método que ya funciona para VIP y FRECUENTE, arriesgando una regresión (una
llave mal cerrada, una variable sobrescrita, o un error de tipeo que afecte
el cálculo del impuesto en la línea inmediatamente siguiente) — violando el
Principio Abierto/Cerrado.

**Patrón aplicado:** las seis responsabilidades se separaron en cuatro capas
cohesivas: `validacion/` (Chain of Responsibility), `descuento/` (Strategy),
y dentro de `service/`, `PedidoRepository` (persistencia) y
`NotificacionPedidoService` (notificación), dejando a `GestorPedidos` como un
orquestador delgado.

**Decisión con justificación — validación como Chain of Responsibility:** se
eligió Chain of Responsibility para la secuencia de validaciones y no una
lista de métodos booleanos invocados en orden, porque las validaciones
tienen una dependencia real de orden y de corte anticipado: si
`ValidadorStock` rechaza el pedido, `ValidadorCliente` ni siquiera debe
ejecutarse. Una alternativa considerada fue un método `validarTodo()` con una
lista de `Predicate<ContextoPedido>`, descartada porque evalúa todos los
predicados aunque el primero ya haya fallado, sin permitir el corte
anticipado que sí ofrece la cadena.

**Decisión con justificación — descuento como Strategy y no como parte de la
cadena:** se eligió Strategy porque las reglas de descuento no tienen
dependencia de orden entre sí ni necesitan "cortar" el flujo: siempre se
aplica exactamente una regla, determinada por el tipo de cliente. Modelarlo
como cadena habría exigido un mecanismo artificial para garantizar que solo
un eslabón module el descuento, cuando un mapa de selección directa
(`SelectorEstrategiaDescuento`) resuelve el problema con menos indirección.

**Hallazgo verificado — bug de cableado en `encadenar()`:** la primera
versión de `GestorPedidos` construyó la cadena así:
`this.primerValidador = stock.encadenar(cliente);`. `ValidadorPedido.encadenar()`
conecta correctamente el eslabón interno (`this.siguiente = siguiente`), pero
**devuelve el eslabón recién agregado**, no el que invoca el método —un
diseño pensado para encadenar fluido (`a.encadenar(b).encadenar(c)`), que
rompe si su valor de retorno se asigna directamente como cabezal de la
cadena. Se comprobó con `rechazaPorStockInsuficiente`: un pedido de 100
unidades contra un stock de 5 se confirmaba igual (`primerValidador` apuntaba
a `ValidadorCliente`, saltándose `ValidadorStock` por completo). Se corrigió
separando la llamada a `encadenar()` de la asignación (`stock.encadenar(cliente); this.primerValidador = stock;`)
porque el objetivo de la Parte 1 es preservar el comportamiento observable,
y este defecto sí lo alteraba.

### Parte 2 — Crecimiento del proyecto (Golden Hammer)

**Antipatrón identificado: Golden Hammer.** Dos semanas después de la Parte
1, se agregaron tres campañas de descuento (`PromocionBlackFriday`,
`PromocionCorporativo`, `PromocionVolumen`) como eslabones 3, 4 y 5 de la
misma cadena de `ValidadorStock` y `ValidadorCliente`, sin evaluar si el
nuevo problema tenía la misma forma que el que resolvió Chain of
Responsibility en la Parte 1:

- **Ausencia de dependencia de orden:** invertir `PromocionVolumen` y
  `PromocionCorporativo` no altera el resultado —la regla "el mayor gana" es
  conmutativa—, a diferencia de `ValidadorStock` y `ValidadorCliente`, donde
  invertir el orden sí cambia qué motivo de rechazo ve el usuario si el
  pedido falla por ambas razones a la vez.
- **Contrato de `ValidadorPedido` incumplido:** la clase fue diseñada para
  decidir si el pedido continúa o se rechaza (`contexto.rechazar(...)`), pero
  `PromocionBlackFriday.ejecutar()` nunca llama a ese método — se "disfraza"
  de validador solo para aprovechar el mecanismo de encadenamiento ya
  existente, violando el Principio de Sustitución de Liskov.
- **Acoplamiento accidental al combinar campañas:** si mercadeo pidiera sumar
  en vez de competir (p. ej. 35% para un cliente corporativo en Black
  Friday), el cambio exigiría tocar cómo las tres campañas escriben sobre el
  mismo `ContextoPedido` que usan `ValidadorStock` y `ValidadorCliente` para
  decidir si un pedido es válido — arriesgando la lógica de seguridad del
  pedido por una regla puramente de mercadeo.

Se reutilizó Chain of Responsibility no porque fuera la herramienta
apropiada, sino porque ya existía y "los eslabones ya sabían conectarse entre
sí" — la definición de Golden Hammer: aplicar una solución conocida a un
problema de forma distinta, en lugar de evaluar qué herramienta corresponde
al nuevo caso. Validar si un pedido puede proceder (un filtro que corta el
flujo) y calcular un porcentaje de descuento (una fórmula sin dependencia de
orden) son problemas de naturaleza distinta, y el segundo ya tenía su patrón
correcto disponible en el mismo proyecto: `EstrategiaDescuento`.

**Hallazgo verificado — el mismo bug de `encadenar()` se repitió:** el código
de ejemplo de esta evolución encadenó los 5 eslabones con
`stock.encadenar(cliente).encadenar(blackFriday).encadenar(corporativo).encadenar(volumen)`
asignado directamente a `primerValidador`, repitiendo el defecto ya
diagnosticado en la Parte 1 (el valor de retorno de esa expresión es el
último eslabón, no el primero). Se corrigió de la misma forma, separando la
construcción de la cadena de la asignación del cabezal, antes de que
afectara ninguna prueba.

**Patrón aplicado:** Strategy, extendiendo el mecanismo existente con
`CalculadorDescuentoFinal`, que combina el descuento por tipo de cliente con
el mayor descuento de campaña activa — la misma regla de negocio
(`Math.max`) que antes, sin escribir sobre un campo mutable compartido desde
clases que no son responsables de validar nada.

**Decisión con justificación — Strategy en vez de más eslabones de cadena:**
se corrigió modelando las tres campañas como `EstrategiaDescuento` porque,
igual que `DescuentoVip` y `DescuentoFrecuente`, calculan un porcentaje sin
depender de un orden de evaluación ni necesitar cortar el flujo del pedido.
La alternativa de mantenerlas en la cadena se descartó explícitamente por
ser la causa del antipatrón diagnosticado.

**Decisión con justificación — eliminar, no comentar, el código descartado:**
`PromocionBlackFriday`, `PromocionCorporativo`, `PromocionVolumen` y el campo
`descuentoCampana` se eliminaron por completo en vez de dejarse comentados
como referencia histórica. Comentar código "por si se necesita después" es
precisamente el mecanismo por el que nace un antipatrón Lava Flow: nadie se
atreve a borrarlo más adelante porque ya no queda claro si cumple alguna
función. El historial de Git —no el código fuente activo— es el lugar
correcto para conservar esa referencia.

**Verificación de equivalencia:** los totales calculados por
`CalculadorDescuentoFinal` son idénticos a los que producían los tres
eslabones mal aplicados: $89.250 para los clientes FRECUENTE y MOROSO,
$535.500 para el cliente VIP (todos con Black Friday activa al 25%, mayor
que sus respectivos descuentos de tipo de cliente), confirmando que la
corrección preservó el comportamiento observable del sistema sin cambiar
ningún resultado.

## Conclusiones

Diagnosticar sin que el antipatrón viniera nombrado de antemano exigió un
tipo de evidencia distinto al de simplemente aplicar un patrón conocido:
contar líneas, niveles de anidamiento y responsabilidades mezcladas para el
God Object, y verificar la ausencia de una propiedad (dependencia de orden)
para el Golden Hammer. Las dos correcciones comparten una misma lección: un
patrón de diseño no es intercambiable con otro solo porque técnicamente
"funcione" —Chain of Responsibility resolvió perfectamente las validaciones
de la Parte 1 precisamente por su corte anticipado, y esa misma propiedad lo
volvió la herramienta equivocada para las campañas de la Parte 2, que no
necesitaban cortar nada, solo calcular un número—. Verificar el código dado
en vez de confiar en él ciegamente resultó más valioso de lo esperado: tanto
el supuesto incorrecto sobre `queryForObject` como el bug de cableado en
`encadenar()` (repetido dos veces, en ambas partes) solo se hicieron visibles
al ejecutar las pruebas contra la base de datos real, no al leer el código.
Eso refuerza la idea central de esta unidad: un buen diagnóstico depende de
evidencia observable y verificada, no de la intuición de que el código
"debería" comportarse como su autor supuso que lo haría.