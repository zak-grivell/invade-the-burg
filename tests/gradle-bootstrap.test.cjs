const {test} = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {distribution, releaseAsset} = require('../.github/actions/prepare-gradle/prepare.cjs');

test('cache path matches the installed Gradle wrapper distribution', () => {
    const result = distribution('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.1-bin.zip\n', '/cache');
    assert.equal(result.zip, path.join('/cache', 'wrapper/dists/gradle-9.8.1-bin/a2l6ltp3k1vth6lwipzm5p2jp/gradle-9.8.1-bin.zip'));
    assert.equal(result.version, '9.8.1');
});
test('unrecognized download hosts fail before fetching', () => {
    assert.throws(() => distribution('distributionUrl=https://example.com/gradle-9.8.1-bin.zip', '/cache'));
});
test('release selection requires the exact archive and a valid checksum', () => {
    const asset = {name: 'gradle-9.8.1-bin.zip', id: 123, digest: 'sha256:' + 'a'.repeat(64)};
    assert.equal(releaseAsset({assets: [asset]}, asset.name), asset);
    assert.throws(() => releaseAsset({assets: [asset]}, 'gradle-9.8.1-all.zip'));
    assert.throws(() => releaseAsset({assets: [{...asset, digest: null}]}, asset.name));
});
