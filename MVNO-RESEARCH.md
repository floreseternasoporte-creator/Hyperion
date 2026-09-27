# HYPERION MOBILE — Investigación: cómo crear nuestra propia operadora (MVNO)

*Documento de investigación — 27 de septiembre de 2026. En español. Hallazgos honestos para planear la operadora Hyperion.*

---

## 1. Qué es una MVNO (operadora móvil virtual)

Una MVNO (*Mobile Virtual Network Operator*) es una compañía de telefonía que **vende servicio móvil usando las torres de otra empresa**. No construye antenas ni compra espectro (eso cuesta miles de millones). Compra capacidad al por mayor (*wholesale*) a una operadora real (en EE.UU.: AT&T, T-Mobile o Verizon), le pone su propia marca, sus planes, su facturación y su atención al cliente, y la revende.

Analogía honesta: es como la marca propia del supermercado — el cereal sale de la misma fábrica, pero la caja, el precio y el servicio son tuyos.

**Dato importante para el usuario:** durante congestión fuerte, algunas redes dan prioridad a sus clientes directos antes que a los de la MVNO (puede notarse en un estadio lleno). No es un problema grave, pero existe.

## 2. Modelos de MVNO (de más fácil a más difícil)

1. **Revendedor ligero (branded reseller / white-label):** la plataforma socia hace TODO (red, SIM/eSIM, facturación, activación). Tú pones marca, planes y marketing. Es el camino rápido.
2. **Proveedor de servicios:** controlas facturación y atención al cliente; la red y el aprovisionamiento los pone el socio.
3. **MVNO completa (full MVNO):** tienes tu propio núcleo de red (HLR/HSS), solo alquilas la radio. Control total, costo y complejidad máximos.

**Recomendación para Hyperion:** empezar como revendedor ligero / white-label con eSIM. Las plataformas reportan lanzamientos en 30–60 días, pero eso es una **estimación de su marketing**, sujeta a contrato mayorista, verificación de identidad/empresa, cumplimiento regulatorio y registro estatal — no es un plazo garantizado.

## 3. Socios y plataformas (habilitadores)

| Plataforma | Qué ofrece | Dato clave hallado |
|---|---|---|
| **Gigs** | "Stripe para planes de teléfono": API + eSIM + SIM física, facturación, aprovisionamiento | Ronda Serie B de $73M (2025); clientes como Klarna. Planes "truly unlimited" sin letra pequeña, con datos prioritarios, hotspot y roaming a México/Canadá. **Nota:** fuentes difieren sobre su red en EE.UU.: material más antiguo menciona AT&T y T-Mobile, mientras que una fuente más reciente (Fierce, 2026) indica que AT&T es su único socio de red en EE.UU. Verificar directamente con Gigs cuál es el acuerdo vigente antes de decidir |
| **Spenza** | Plataforma MVNE con API, SIM control, facturación multi-operadora | Tiene calculadora de costo de lanzamiento y punto de equilibrio; orientada a marcas que quieren lanzar su MVNO |
| **1GLOBAL** | Plataforma full-stack de conectividad/eSIM | Alternativa sólida para no depender de un solo proveedor |
| **Telnyx** | Plataforma de comunicaciones con API (voz, SMS, IoT/SIM) | SDKs en Python/Go/Node; modelo pay-as-you-go sin mínimos; eSIM programable vía API |
| **Netcracker** | Plataforma MVNO gestionada en la nube | "Lanzar una marca móvil se vuelve tan simple como configurar planes" |
| **PortaOne / PortaBilling** | BSS para MVNOs (facturación en tiempo real, bundles prepago) | Serie de guías "Launching Your MVNO" paso a paso |
| **Covalense Csmart MVNx** | BSS multi-tenant para MVNO/MVNE | Un nuevo tenant se aprovisiona en minutos por configuración |

## 4. eSIM: el camino moderno

- Los iPhone 14 en adelante (EE.UU.) **ya no traen ranura física**: solo eSIM.
- Una MVNO "eSIM-first" no envía plásticos: el cliente compra en la app, paga, y descarga el perfil eSIM con un QR o directo desde la app.
- Lo difícil NO es el QR: es el ciclo de vida (qué pasa si el pago funciona pero la activación falla, cambio de plan, reemplazo de SIM, suspensión). La plataforma debe manejar todos esos estados.
- Seguridad: el aprovisionamiento remoto sigue estándares GSMA; el socio debe estar certificado.

## 5. Costos aproximados (honestos)

No hay una cifra única, pero los rangos reales del mercado son:

- **Setup/onboarding con la plataforma:** de $0 (modelos pay-as-you-grow) a decenas de miles de USD según el socio.
- **Cuota mensual de plataforma:** fija + por línea activa, o 100% variable por uso.
- **Wholesale (lo que pagas por el servicio):** se paga **por GB de datos, por minuto de voz y por SMS**. El GB mayorista cuesta una fracción del retail (referencia retail: planes "flexibles" cobran ~$10/GB al público; el mayorista es bastante menor y baja con volumen).
- **Costos grandes que la gente olvida:** marketing y adquisición de clientes (el CAC varía muchísimo por canal y nicho; una cifra de ~$150 por cliente apareció en un escenario de ISP 5G, no como promedio de la industria MVNO — hay que modelarlo con datos propios), soporte al cliente 24/7, facturación/impuestos telecom, fraude y contracargos.
- **Referencia retail EE.UU. (2026):** ilimitados baratos desde **$25/mes** (Visible, US Mobile, Tello…); eso marca el techo de precio al que competiríamos.

**Verdad incómoda (importante):** cada GB que un cliente consume **nos cuesta dinero real** al por mayor. Por eso **ninguna operadora puede regalar internet gratis e ilimitado para siempre** — quien lo prometa miente o lo subsidia con otra cosa. Nuestro diferencial realista: planes baratos + la app Hyperion (VPN, antivirus, ahorro de datos) incluida.

## 6. Regulación en EE.UU. (FCC y estatal)

- **No necesitas licencia de espectro** (no transmites; revendes).
- **Portabilidad numérica:** la FCC exige que portar un número entre operadoras se complete en **1 día hábil** (en la práctica, horas). La operadora anterior no puede negarse.
- **Etiquetas de banda ancha (*broadband labels*):** la FCC exige publicar precios, velocidades y cargos de forma estandarizada, como etiqueta nutricional.
- **Contribuciones USAC / Formulario 499-A:** los proveedores de telecomunicaciones interestatales suelen tener obligaciones de reporte y contribución al Fondo de Servicio Universal; aplica según el tipo exacto de servicio que se ofrezca (voz vs. datos). Requiere análisis con abogado.
- **Registros estatales:** varios estados exigen registro como proveedor de telecomunicaciones; no basta con lo federal.
- **CPNI (privacidad):** reglas estrictas sobre el uso y protección de la información de red del cliente (a quién llamó, cuándo, cuánto).
- **E911:** obligación de enrutar llamadas de emergencia con ubicación.
- **CALEA:** capacidad de interceptación legal bajo orden judicial.
- **STIR/SHAKEN y robocalls:** si se ofrece voz, hay obligaciones de autenticación de llamadas y mitigación de robollamadas.
- **Impuestos y cargos:** los impuestos telecom de EE.UU. los paga el cliente final o los absorbe la operadora; hay que definirlo en el precio.
- **KYC/identidad:** verificación de identidad del cliente en la activación.
- Se recomienda abogado telecom para el contrato mayorista **y** el mapa regulatorio (un mal contrato o una obligación incumplida cuesta más que el acceso).

## 7. Tiempos realistas

| Vía | Tiempo típico |
|---|---|
| White-label eSIM con plataforma lista (Gigs/Spenza) | **30–60 días** según las plataformas (no garantizado; sujeto a contrato y cumplimiento) |
| Plataforma cloud (migración + configuración) | ~90 días |
| MVNO tradicional con sistemas propios | hasta 18 meses |

Casos reales: MyRepublic lanzó en 90 días sobre stack cloud; operadores legacy tardan 18 meses.

## 8. Ruta recomendada para Hyperion Mobile (paso a paso)

1. **Definir el nicho:** ¿a quién le vendemos? (ej. latinos en EE.UU., gamers, viajeros). Sin nicho no hay marketing rentable.
2. **Elegir plataforma:** pedir propuestas a **Gigs** y **Spenza** (y comparar con 1GLOBAL): red disponible (AT&T/T-Mobile), precio mayorista por GB/min/SMS, mínimos mensuales, eSIM API, portabilidad, impuestos.
3. **Modelo de negocio:** planes prepago simples (ej. 5 GB / 15 GB / ilimitado) con precio que deje margen sobre el wholesale + CAC.
4. **Marca y app:** "Hyperion Mobile" dentro de la app Hyperion: compra, activación eSIM, gestión de línea, soporte.
5. **Legal:** constituir empresa, abogado telecom, contrato mayorista, cumplir FCC (etiquetas, portabilidad, KYC).
6. **Piloto:** 100–500 líneas de prueba (amigos/familia/comunidad) con eSIM.
7. **Soporte:** chat en la app + base de conocimiento (puede empezar pequeño y tercerizado).
8. **Lanzamiento público:** marketing al nicho, con la lista de espera de la app como primeros clientes.
9. **Escalar:** negociar mejores tarifas mayoristas con volumen; evaluar segunda red.
10. **Nunca prometer** "internet gratis": prometer precio justo + la app Hyperion incluida.

## 9. Fuentes

- https://www.fierce-network.com/wireless/gigs-dials-new-mvno-model-att
- https://www.fierce-network.com/wireless/gigs-latest-gig-makes-klarna-mvno-sort
- https://www.sdxcentral.com/news/mvno-enabler-gigs-raises-73m-in-series-b-funding/
- https://medium.com/@telecomhub360/how-to-launch-an-esim-first-mvno-what-the-setup-actually-looks-like-a5890440fc16
- https://dev.to/sheerbittech/white-label-mvno-a-simple-guide-to-starting-your-own-mobile-network-1592
- https://spenza.com/mvno/best-mvno/
- https://www.wirelessdealergroup.com/post/what-an-mvno-actually-is-plain-english-guide
- https://github.com/team-telnyx/telnyx-code-examples/blob/HEAD/provision-esim-go/README.md

---

*Conclusión: es 100% viable lanzar Hyperion Mobile como MVNO eSIM-first en 1–3 meses típicos con un socio como Gigs/Spenza (plazo reportado por las plataformas, sujeto a contrato mayorista y cumplimiento regulatorio), sin torres ni espectro. El costo real está en el wholesale por GB, el marketing (CAC a modelar con datos propios) y el soporte. "Gratis e ilimitado" no existe como modelo de negocio: la honestidad es no prometerlo.*
