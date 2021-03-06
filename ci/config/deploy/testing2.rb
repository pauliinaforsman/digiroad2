role :app, %w{web@testing2}
role :web, %w{web@testing2}
server 'testing2', user: 'web', roles: %w{web app}

namespace :deploy do
  task :start_vallu_server do
    on roles(:all) do
      execute "killall -q node; exit 0"
      execute "cd #{release_path} && chmod 700 start_vallu_server.sh"
      execute "cd #{release_path} && nohup ./start_vallu_server.sh"
    end
  end

  # after :publishing, :start_vallu_server
end