// Bedrock end-to-end test: joins the CI test server through Geyser as a Bedrock client (offline login) and checks what
// GOML sends to Bedrock players. The server side is src/gametest/java/draylar/goml/test/E2ECommands.java, driven with
// "/gomltest <step>" commands. Results go to $RESULTS_FILE, the exit code is 0 only when every check passed.
import bedrock from 'bedrock-protocol'
import { randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'

// The native RakNet backend needs a C++ build during npm install, the pure JS one is the fallback
function raknetBackend () {
  if (process.env.RAKNET_BACKEND) return process.env.RAKNET_BACKEND
  try {
    createRequire(import.meta.url)('raknet-native')
    return 'raknet-native'
  } catch {
    return 'jsp-raknet'
  }
}

const host = process.env.BEDROCK_HOST ?? '127.0.0.1'
const port = Number(process.env.BEDROCK_PORT ?? 19132)
const resultsFile = process.env.RESULTS_FILE ?? 'bedrock-results.json'
const OWNER = 'E2EOwner'

const results = []
const received = []
let runtimeId = null
let closed = null

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const strip = (text) => String(text ?? '').replace(/§./g, '')

function record (name, ok, details = '') {
  results.push({ name, ok, details: String(details) })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${details ? ` - ${details}` : ''}`)
}

// Chat, system and action bar text (Geyser sends the action bar as a jukebox popup)
function textOf (packet) {
  if (packet.name === 'text') return strip([packet.params.message, ...(packet.params.parameters ?? [])].join(' '))
  if (packet.name === 'set_title') return strip(packet.params.text)
  return null
}

async function waitFor (predicate, timeoutMs, from = 0) {
  const deadline = Date.now() + timeoutMs
  let index = from
  while (Date.now() < deadline && !closed) {
    for (; index < received.length; index++) {
      if (predicate(received[index])) return received[index]
    }
    await sleep(100)
  }
  return null
}

function packetsSince (from, predicate) {
  return received.slice(from).filter(predicate)
}

function sendCommand (command) {
  const packet = {
    command,
    origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n },
    internal: false
  }
  // The version field changed from a number to a string in newer protocol versions
  try {
    client.queue('command_request', { ...packet, version: 'latest' })
  } catch {
    client.queue('command_request', { ...packet, version: 52 })
  }
}

// Runs "/gomltest <step>" and waits for the server's "[gomltest] <step> ok ..." answer.
// "from" is where to look for what the step caused, which can arrive before the answer.
async function step (name) {
  const from = received.length
  sendCommand(`/gomltest ${name}`)
  const answer = await waitFor((packet) => textOf(packet)?.includes(`[gomltest] ${name} `), 10000, from)
  const text = answer ? textOf(answer) : null
  return { from, ok: Boolean(text?.includes(`[gomltest] ${name} ok`)), text: text ?? 'no answer' }
}

async function expectText (name, pattern, from, timeoutMs = 5000) {
  const found = await waitFor((packet) => pattern.test(textOf(packet) ?? ''), timeoutMs, from)
  record(name, Boolean(found), found ? `"${textOf(found)}"` : `nothing matching ${pattern}`)
}

// Geyser may advertise a newer Bedrock version than this library knows, then join with the newest one it knows
// (Geyser accepts several versions). Pinging until it answers also waits for Geyser to finish starting.
async function bedrockVersion () {
  if (process.env.BEDROCK_VERSION) return process.env.BEDROCK_VERSION
  const { Versions, CURRENT_VERSION } = createRequire(import.meta.url)('bedrock-protocol/src/options.js')
  for (let attempt = 0; attempt < 15; attempt++) {
    try {
      const advertisement = await bedrock.ping({ host, port, timeout: 2000 })
      const version = Object.keys(Versions).find((name) => Versions[name] === Number(advertisement.protocol))
      console.log(`Server advertises protocol ${advertisement.protocol} (${advertisement.version}), ${version ? `joining as ${version}` : `unknown here, joining as ${CURRENT_VERSION}`}`)
      return version ?? CURRENT_VERSION
    } catch {
      await sleep(2000)
    }
  }
  console.log(`No answer to pings, trying ${CURRENT_VERSION}`)
  return CURRENT_VERSION
}

const client = bedrock.createClient({
  host,
  port,
  username: 'BedrockBot',
  offline: true,
  connectTimeout: 30000,
  skipPing: true,
  version: await bedrockVersion(),
  raknetBackend: raknetBackend()
})
console.log(`Connecting to ${host}:${port} as Bedrock ${client.options.version} with ${client.options.raknetBackend}`)

client.on('packet', (packet) => {
  const { name, params } = packet.data
  received.push({ name, params, time: Date.now() })
  if (name === 'start_game') runtimeId = String(params.runtime_entity_id)
  const text = textOf({ name, params })
  if (text) console.log(`<- ${name}: ${text}`)
})
client.on('kick', (packet) => { closed = `kicked: ${JSON.stringify(packet)}` })
client.on('close', () => { closed ??= 'connection closed' })
client.on('error', (error) => { console.error(error); closed ??= `error: ${error.message}` })

async function run () {
  const spawned = await Promise.race([
    new Promise((resolve) => client.once('spawn', () => resolve(true))),
    sleep(90000).then(() => false)
  ])
  record('Join through Geyser', spawned && !closed, spawned ? `Bedrock ${client.options?.version}` : (closed ?? 'no spawn within 90s'))
  if (!spawned || closed) return
  // Let the server finish the join (Geyser sends a lot on spawn)
  await sleep(3000)

  const detect = await step('detect')
  record('GOML detects the Bedrock player', detect.text.includes('bedrock=true'), detect.text)

  const setup = await step('setup')
  record('Test claim created', setup.ok, setup.text)
  if (!setup.ok) return

  const deny = await step('deny')
  await expectText('Protection message is translated and names the owner', new RegExp(`protected by ${OWNER}'s claim`, 'i'), deny.from)

  // The claim was created around the bot, so enter/leave messages start after moving out and back in
  await step('leave')
  const enter = await step('enter')
  await expectText('Entering message', new RegExp(`Entering ${OWNER}'s claim`), enter.from)
  const leave = await step('leave')
  await expectText('Leaving message', new RegExp(`Leaving ${OWNER}'s claim`), leave.from)
  await step('enter')

  // Bedrock can't show block markers, GOML draws the outline with dust (Geyser: falling dust level events)
  const goggles = await step('goggles')
  await sleep(8000)
  const particles = packetsSince(goggles.from, (packet) => packet.name === 'level_event' || packet.name === 'spawn_particle_effect').length
  record('Goggles draw the claim outline with particles', particles > 0, `${particles} particle packets in 8s`)

  // Chaos Zone gives Strength while inside, refreshed about once a second instead of every tick
  const chaos = await step('chaos')
  await sleep(6000)
  const ownEffects = (packet) => packet.name === 'mob_effect' && (runtimeId === null || String(packet.params.runtime_entity_id) === runtimeId)
  const effectUpdates = packetsSince(chaos.from, ownEffects).length
  record('Chaos Zone applies its effect', effectUpdates > 0, `${effectUpdates} effect packets in 6s`)
  record('Chaos Zone doesn\'t spam effect updates', effectUpdates <= 15, `${effectUpdates} effect packets in 6s (every tick would be about 120)`)
  const noChaos = await step('nochaos')
  const removed = await waitFor((packet) => ownEffects(packet) && packet.params.event_id === 'remove', 3000, noChaos.from)
  record('Chaos Zone effect is removed with the augment', Boolean(removed))

  // Server-side menus work on Bedrock through Geyser
  const beforeList = received.length
  sendCommand('/goml list')
  const opened = await waitFor((packet) => packet.name === 'container_open', 8000, beforeList)
  record('/goml list opens a menu', Boolean(opened), opened ? `window type ${opened.params.window_type}` : 'no container_open')

  await step('cleanup')
}

try {
  await Promise.race([run(), sleep(200000).then(() => { throw new Error('timed out') })])
} catch (error) {
  record('Test run', false, error.message)
}

if (closed) record('Stayed connected', false, closed)
const rawKeys = received.map(textOf).filter((text) => text && /\b(text|block|item)\.goml\.[a-z_.]+/.test(text))
record('No untranslated GOML text', rawKeys.length === 0, rawKeys.slice(0, 3).join(' / '))

writeFileSync(resultsFile, JSON.stringify(results, null, 2))
client.close()
const failed = results.filter((result) => !result.ok)
console.log(failed.length ? `${failed.length} of ${results.length} checks failed` : `All ${results.length} checks passed`)
process.exit(failed.length ? 1 : 0)
