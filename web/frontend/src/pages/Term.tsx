import { useEffect, useState } from 'react';
import * as api from '../api';
import { navigate, hrefOf } from '../router';

export function TermText() {
  return (
    <div className="prose">
      <p>
        Este site apresenta dados de redes Wi-Fi coletados pelo projeto <strong>Wardriver</strong>, de autoria de Fernando
        Henrique Campanhã Abrão e Aline Almeida Vajalegre, para fins de estudo sobre segurança de redes sem fio.
      </p>
      <h2>O que foi coletado</h2>
      <p>
        Apenas as informações que todo ponto de acesso transmite publicamente, a qualquer aparelho ao alcance: nome da rede (SSID),
        identificador do equipamento (BSSID), canal, intensidade do sinal, tipo de criptografia e presença de WPS. A cada rede foi
        associada a posição do coletor no momento da captura.
      </p>
      <h2>O que não foi coletado</h2>
      <p>
        Nenhuma senha, nenhum conteúdo de comunicação, nenhum endereço de aparelho de usuário. O equipamento não se conectou a
        nenhuma rede nem interferiu em seu funcionamento.
      </p>
      <h2>Ao prosseguir, você se compromete a</h2>
      <ol>
        <li>usar estes dados somente para estudo, pesquisa ou conscientização sobre segurança;</li>
        <li>não tentar acessar, invadir, testar ou perturbar qualquer rede aqui listada;</li>
        <li>não usar os dados para identificar, localizar ou monitorar pessoas;</li>
        <li>não copiar em massa, redistribuir ou comercializar os dados;</li>
        <li>respeitar a Lei Geral de Proteção de Dados (Lei 13.709/2018) e a legislação aplicável.</li>
      </ol>
      <p>
        O nome e a localização de uma rede podem, em alguns casos, estar ligados a uma pessoa. Trate o que encontrar aqui com esse
        cuidado.
      </p>
      <p>O uso em desacordo com este termo é de responsabilidade exclusiva de quem o praticar.</p>
    </div>
  );
}

export default function Term({
  accepted,
  version,
  next,
  onAccepted,
  onRevoked,
}: {
  accepted: boolean;
  version: string;
  next: string | null;
  onAccepted: () => void;
  onRevoked: () => void;
}) {
  const [agree, setAgree] = useState(false);
  const [password, setPassword] = useState('');

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function accept() {
    setBusy(true);
    setError('');
    try {
      await api.login(version, password);
      onAccepted();

      const target = next && /^\/(redes|analise)?$/.test(next.split('?')[0]) ? next : '/';
      location.hash = `#${target}`;
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function revoke() {
    try {
      await api.revokeTerm();
    } catch {

    }
    onRevoked();
    navigate('/sobre');
  }

  return (
    <div className="modal-backdrop">
      <section className="modal" role="dialog" aria-modal="true" aria-labelledby="term-title">
        <header className="modal-head">
          <p className="eyebrow">Acesso restrito · termo de compromisso versão {version}</p>
          <h1 id="term-title">Entrar</h1>
        </header>
        <div className="modal-body">
          <TermText />
        </div>
        {accepted ? (
          <div className="modal-foot">
            <p className="ok-line">Você aceitou o termo nesta sessão. O aceite vale até fechar o navegador.</p>
            <div className="row">
              <a className="btn btn-solid" href={hrefOf('/')}>
                Ir ao painel
              </a>
              <button className="btn" type="button" onClick={revoke}>
                Revogar aceite
              </button>
            </div>
          </div>
        ) : (
          <form
            className="modal-foot"
            onSubmit={(e) => {
              e.preventDefault();
              if (agree && password) void accept();
            }}
          >
            <label className="field">
              <span>Senha</span>
              <input
                type="password"
                autoComplete="current-password"
                autoFocus
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
            <label className="check">
              <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
              <span>Li o termo e me comprometo a cumpri-lo.</span>
            </label>
            {error && (
              <p className="error" role="alert">
                {error}
              </p>
            )}
            <div className="row">
              <button className="btn btn-solid" type="submit" disabled={!agree || !password || busy}>
                {busy ? 'Entrando…' : 'Entrar'}
              </button>
              <a className="btn" href={hrefOf('/sobre')}>
                Sair
              </a>
            </div>
          </form>
        )}
      </section>
    </div>
  );
}
