const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const {execFileSync, spawnSync} = require('node:child_process');

function distribution(properties, gradleHome) {
    const value = properties.match(/^distributionUrl=(.+)$/m)?.[1].trim().replace(/\\:/g, ':');
    const match = value?.match(/^https:\/\/(?:services|downloads)\.gradle\.org\/distributions\/gradle-([\d.]+)-(bin|all)\.zip$/);
    if (!match) throw new Error('Expected an official, versioned Gradle distribution URL');
    const [, version, type] = match;
    const hash = BigInt('0x' + crypto.createHash('md5').update(value).digest('hex')).toString(36);
    const name = `gradle-${version}-${type}.zip`;
    const directory = path.join(gradleHome, 'wrapper', 'dists', name.slice(0, -4), hash);
    return {version, name, directory, zip: path.join(directory, name)};
}

function releaseAsset(release, name) {
    const asset = release.assets.find(asset => asset.name === name);
    if (!asset || !Number.isSafeInteger(asset.id) || !/^sha256:[a-f0-9]{64}$/.test(asset.digest)) {
        throw new Error('Official release asset or SHA-256 digest missing');
    }
    return asset;
}

function main() {
    const config = distribution(fs.readFileSync('gradle/wrapper/gradle-wrapper.properties', 'utf8'),
        process.env.GRADLE_USER_HOME || path.join(os.homedir(), '.gradle'));
    if (fs.existsSync(config.zip + '.ok') && fs.existsSync(path.join(config.directory, `gradle-${config.version}`, 'bin', 'gradle'))) {
        console.log('Gradle distribution already installed in wrapper cache');
        return;
    }
    const release = JSON.parse(execFileSync('gh', ['api', `repos/gradle/gradle-distributions/releases/tags/v${config.version}`], {encoding: 'utf8'}));
    const asset = releaseAsset(release, config.name);
    fs.mkdirSync(config.directory, {recursive: true});
    const temporary = config.zip + '.api-download';
    try {
        const fd = fs.openSync(temporary, 'w');
        let result;
        try {
            result = spawnSync('gh', ['api', '-H', 'Accept: application/octet-stream',
                `repos/gradle/gradle-distributions/releases/assets/${asset.id}`],
                {stdio: ['ignore', fd, 'inherit'], timeout: 300000});
        } finally { fs.closeSync(fd); }
        if (result.error || result.status !== 0) throw result.error || new Error('Gradle asset download failed');
        const digest = crypto.createHash('sha256').update(fs.readFileSync(temporary)).digest('hex');
        if ('sha256:' + digest !== asset.digest) throw new Error('Gradle distribution SHA-256 mismatch');
        fs.renameSync(temporary, config.zip);
        console.log(`Verified ${config.name} in wrapper cache; wrapper will unpack it`);
    } finally { fs.rmSync(temporary, {force: true}); }
}

module.exports = {distribution, releaseAsset};
if (require.main === module) main();
