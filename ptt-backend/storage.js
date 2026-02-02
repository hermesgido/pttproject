const fs = require('fs');
const path = require('path');
const DATA_PATH = path.join(__dirname, 'data.json');

function load() {
  try {
    if (!fs.existsSync(DATA_PATH)) {
      const initial = { companies: [], devices: [], channels: [], memberships: [] };
      fs.writeFileSync(DATA_PATH, JSON.stringify(initial, null, 2));
      return initial;
    }
    const raw = fs.readFileSync(DATA_PATH, 'utf8');
    return JSON.parse(raw || '{}');
  } catch {
    return { companies: [], devices: [], channels: [], memberships: [] };
  }
}

function save(data) {
  fs.writeFileSync(DATA_PATH, JSON.stringify(data, null, 2));
}

function nextId(prefix) {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}`;
}

function generateAccountNumber(devices, companyId) {
  let acc;
  const used = new Set(devices.filter(d => d.companyId === companyId).map(d => d.accountNumber));
  do {
    acc = Math.floor(10000 + Math.random() * 90000).toString();
  } while (used.has(acc));
  return acc;
}

module.exports = {
  load,
  save,
  nextId,
  generateAccountNumber,
};

