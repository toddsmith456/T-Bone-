// Non-interactive Zapstore v2 publisher for T-Bone fork.
//
// Uses the official github.com/zapstore/zsp/v2 library to fetch the latest
// signed release APK from GitHub, optionally create/renew the NIP-C1 ownership
// proof (kind 30509) using the release signing certificate/key, upload media
// to Zapstore's Blossom CDN, and publish the NIP-82 app/asset/release events.
package main

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"syscall"

	gonostr "github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip19"
	"github.com/zapstore/zsp/v2"
)

type pemProofSigner struct {
	privateKey     crypto.PrivateKey
	certificate    *x509.Certificate
	certHash       string
	ownerPubkeyHex string
}

func (s pemProofSigner) SignProof(ctx context.Context, createdAt, expiry int64) (zsp.ProofSignature, error) {
	if err := ctx.Err(); err != nil {
		return zsp.ProofSignature{}, err
	}
	message := fmt.Sprintf(
		"Verifying at %d until %d that I control the following Nostr public key: %s",
		createdAt, expiry, s.ownerPubkeyHex,
	)
	digest := sha256.Sum256([]byte(message))

	var sig []byte
	var err error
	switch key := s.privateKey.(type) {
	case *rsa.PrivateKey:
		sig, err = rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
		if err == nil {
			err = rsa.VerifyPKCS1v15(&key.PublicKey, crypto.SHA256, digest[:], sig)
		}
	case *ecdsa.PrivateKey:
		sig, err = ecdsa.SignASN1(rand.Reader, key, digest[:])
		if err == nil && !ecdsa.VerifyASN1(&key.PublicKey, digest[:], sig) {
			err = errors.New("self-verify ECDSA proof signature failed")
		}
	default:
		return zsp.ProofSignature{}, fmt.Errorf("unsupported signing key type %T", s.privateKey)
	}
	if err != nil {
		return zsp.ProofSignature{}, fmt.Errorf("sign NIP-C1 proof: %w", err)
	}

	return zsp.ProofSignature{
		PublicKey:       s.ownerPubkeyHex,
		CertificateHash: s.certHash,
		Signature:       sig,
	}, nil
}

func loadPEMCertAndKey(certPath, keyPath string) (*x509.Certificate, crypto.PrivateKey, string, error) {
	certBytes, err := os.ReadFile(certPath)
	if err != nil {
		return nil, nil, "", fmt.Errorf("read cert PEM: %w", err)
	}
	var cert *x509.Certificate
	for rest := certBytes; len(rest) > 0; {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		if block.Type == "CERTIFICATE" {
			parsed, parseErr := x509.ParseCertificate(block.Bytes)
			if parseErr != nil {
				return nil, nil, "", fmt.Errorf("parse X.509 certificate: %w", parseErr)
			}
			cert = parsed
			break
		}
	}
	if cert == nil {
		return nil, nil, "", errors.New("no CERTIFICATE PEM block found")
	}

	keyBytes, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, nil, "", fmt.Errorf("read key PEM: %w", err)
	}
	var privKey crypto.PrivateKey
	for rest := keyBytes; len(rest) > 0; {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		if !strings.Contains(block.Type, "PRIVATE KEY") {
			continue
		}
		if k, e := x509.ParsePKCS8PrivateKey(block.Bytes); e == nil {
			privKey = k
			break
		}
		if k, e := x509.ParsePKCS1PrivateKey(block.Bytes); e == nil {
			privKey = k
			break
		}
		if k, e := x509.ParseECPrivateKey(block.Bytes); e == nil {
			privKey = k
			break
		}
	}
	if privKey == nil {
		return nil, nil, "", errors.New("no supported PRIVATE KEY PEM block found")
	}

	signer, ok := privKey.(crypto.Signer)
	if !ok {
		return nil, nil, "", fmt.Errorf("private key %T does not implement crypto.Signer", privKey)
	}
	if !publicKeysEqual(signer.Public(), cert.PublicKey) {
		return nil, nil, "", errors.New("private key does not match certificate public key")
	}

	sum := sha256.Sum256(cert.Raw)
	return cert, privKey, hex.EncodeToString(sum[:]), nil
}

func publicKeysEqual(a, b crypto.PublicKey) bool {
	switch ak := a.(type) {
	case *rsa.PublicKey:
		bk, ok := b.(*rsa.PublicKey)
		return ok && ak.Equal(bk)
	case *ecdsa.PublicKey:
		bk, ok := b.(*ecdsa.PublicKey)
		return ok && ak.Equal(bk)
	default:
		return false
	}
}

func isLowerHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	decoded, err := hex.DecodeString(s)
	return err == nil && hex.EncodeToString(decoded) == s
}

func resolveOwnerPubkey(explicit, signWith string) (string, error) {
	explicit = strings.TrimSpace(explicit)
	if explicit != "" {
		if strings.HasPrefix(explicit, "npub1") {
			prefix, data, err := nip19.Decode(explicit)
			if err != nil || prefix != "npub" {
				return "", fmt.Errorf("invalid owner npub: %w", err)
			}
			pk, ok := data.(string)
			if !ok || !isLowerHex64(strings.ToLower(pk)) {
				return "", errors.New("decoded owner npub is not a valid 64-char hex pubkey")
			}
			return strings.ToLower(pk), nil
		}
		lower := strings.ToLower(explicit)
		if !isLowerHex64(lower) {
			return "", errors.New("owner_pubkey must be an npub1... or 64-char hex pubkey")
		}
		return lower, nil
	}

	if strings.HasPrefix(signWith, "bunker://") {
		parsed, err := url.Parse(signWith)
		if err != nil {
			return "", errors.New("SIGN_WITH is not a valid bunker:// URL")
		}
		host := strings.ToLower(parsed.Host)
		if !isLowerHex64(host) {
			return "", errors.New("bunker:// URL host is not a valid 64-char hex pubkey")
		}
		return host, nil
	}

	if strings.HasPrefix(signWith, "nsec1") {
		prefix, data, err := nip19.Decode(signWith)
		if err != nil || prefix != "nsec" {
			return "", errors.New("SIGN_WITH is not a valid nsec1... key")
		}
		sk, ok := data.(string)
		if !ok {
			return "", errors.New("unexpected nsec decode result")
		}
		pk, err := gonostr.GetPublicKey(sk)
		if err != nil {
			return "", fmt.Errorf("derive pubkey from nsec: %w", err)
		}
		return strings.ToLower(pk), nil
	}

	return "", errors.New("SIGN_WITH must start with bunker:// or nsec1")
}

func main() {
	configPath := flag.String("config", "zapstore.yaml", "Path to zapstore.yaml")
	certPath := flag.String("cert", "", "Path to PEM signing certificate for NIP-C1 proof")
	keyPath := flag.String("key", "", "Path to PEM signing private key for NIP-C1 proof")
	ownerPubkey := flag.String("owner-pubkey", "", "Optional owner Nostr pubkey (npub1... or 64-char hex)")
	overwriteRelease := flag.Bool("overwrite-release", false, "Allow republishing an equal versionCode")
	overwriteAppEvent := flag.Bool("overwrite-app-event", false, "Republish kind 32267 even if unchanged")
	flag.Parse()

	signWith := strings.TrimSpace(os.Getenv("SIGN_WITH"))
	if signWith == "" {
		fmt.Fprintln(os.Stderr, "error: SIGN_WITH environment variable is required")
		os.Exit(1)
	}
	// Normalize HTML-escaped ampersands in case a bunker:// URI was copied from rendered Markdown.
	if strings.Contains(signWith, "&amp;") {
		signWith = strings.ReplaceAll(signWith, "&amp;", "&")
	}
	if err := os.Setenv("SIGN_WITH", signWith); err != nil {
		fmt.Fprintf(os.Stderr, "error: set SIGN_WITH: %v\n", err)
		os.Exit(1)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg, err := zsp.LoadConfig(*configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: load config: %v\n", err)
		os.Exit(1)
	}

	onProgress := func(p zsp.Progress) {
		if p.Phase == "warning" {
			fmt.Fprintf(os.Stderr, "[warning] %s\n", p.Target)
			return
		}
		if p.Total > 0 {
			fmt.Fprintf(os.Stderr, "[%s/%s] %s (%d/%d)\n", p.Operation, p.Phase, p.Target, p.Completed, p.Total)
		} else if p.Target != "" {
			fmt.Fprintf(os.Stderr, "[%s/%s] %s\n", p.Operation, p.Phase, p.Target)
		}
	}

	candidates, err := zsp.Fetch(ctx, cfg.Fetch, zsp.FetchOptions{
		SkipHTTPCache: true,
		OnProgress:    onProgress,
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: fetch release APK: %v\n", err)
		os.Exit(1)
	}
	defer func() {
		for _, c := range candidates {
			_ = c.Close()
		}
	}()
	if len(candidates) != 1 {
		fmt.Fprintf(os.Stderr, "error: expected 1 verified APK candidate, got %d\n", len(candidates))
		os.Exit(1)
	}
	apk := candidates[0]
	fmt.Fprintf(os.Stderr, "Verified APK: %s v%s (versionCode %d), cert=%s, sha256=%s\n",
		apk.AppID, apk.VersionName, apk.VersionCode, apk.CertificateHash, apk.Hash)

	var proofSigner zsp.ProofSigner
	if *certPath != "" || *keyPath != "" {
		if *certPath == "" || *keyPath == "" {
			fmt.Fprintln(os.Stderr, "error: both -cert and -key must be supplied together")
			os.Exit(1)
		}
		cert, privKey, certHash, err := loadPEMCertAndKey(*certPath, *keyPath)
		if err != nil {
			fmt.Fprintf(os.Stderr, "error: load signing material: %v\n", err)
			os.Exit(1)
		}
		if certHash != apk.CertificateHash {
			fmt.Fprintf(os.Stderr, "error: signing cert (%s) does not match APK cert (%s)\n", certHash, apk.CertificateHash)
			os.Exit(1)
		}
		ownerHex, err := resolveOwnerPubkey(*ownerPubkey, signWith)
		if err != nil {
			fmt.Fprintf(os.Stderr, "error: resolve owner pubkey: %v\n", err)
			os.Exit(1)
		}
		fmt.Fprintf(os.Stderr, "NIP-C1 proof signer ready for owner pubkey %s\n", ownerHex)
		proofSigner = pemProofSigner{
			privateKey:     privKey,
			certificate:    cert,
			certHash:       certHash,
			ownerPubkeyHex: ownerHex,
		}
	}

	result, err := zsp.Publish(ctx, cfg.Publish, apk, zsp.PublishOptions{
		OverwriteRelease:  *overwriteRelease,
		OverwriteAppEvent: *overwriteAppEvent,
		OnProgress:        onProgress,
		ProofSigner:       proofSigner,
	})
	if err != nil {
		if errors.Is(err, zsp.ErrAlreadyPublished) {
			fmt.Fprintf(os.Stderr, "Release v%s (versionCode %d) is already published on Zapstore.\n", apk.VersionName, apk.VersionCode)
			os.Exit(0)
		}
		if result != nil {
			enc := json.NewEncoder(os.Stderr)
			enc.SetIndent("", "  ")
			_ = enc.Encode(result)
		}
		fmt.Fprintf(os.Stderr, "error: publish to Zapstore: %v\n", err)
		os.Exit(1)
	}

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(result); err != nil {
		fmt.Fprintf(os.Stderr, "error: encode result: %v\n", err)
		os.Exit(1)
	}
}
